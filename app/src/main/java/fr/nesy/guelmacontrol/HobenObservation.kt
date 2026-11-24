package fr.nesy.guelmacontrol

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun hobenDeviceKey(id:String)=MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
internal data class HobenMeasure(val time:Long,val ambient:Double?,val target:Double?,val power:Int?,val state:Int,val overrideActive:Boolean,val outdoor:Double?=null,val weatherPlace:String?=null)

internal class HobenHistoryStore(private val c:Context):SQLiteOpenHelper(c,"hoben_history.db",null,2){
    override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE measures(device TEXT NOT NULL,time INTEGER NOT NULL,ambient REAL,target REAL,power INTEGER,state INTEGER,override_active INTEGER,outdoor REAL,weather_place TEXT,PRIMARY KEY(device,time))")}
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){if(oldVersion<2){db.execSQL("ALTER TABLE measures ADD COLUMN outdoor REAL");db.execSQL("ALTER TABLE measures ADD COLUMN weather_place TEXT")}}
    fun record(s:HobenSnapshot,weather:HouseWeather?=null):Boolean=synchronized(lock){
        if(!HobenObservation.enabled(c)||s.deviceKey.isEmpty()||(s.ambient==null&&s.target==null))return@synchronized false
        val db=writableDatabase
        db.delete("measures","time<?",arrayOf((System.currentTimeMillis()-90L*86400000).toString()))
        db.rawQuery("SELECT MAX(time) FROM measures WHERE device=?",arrayOf(s.deviceKey)).use{
            if(it.moveToFirst()&&!it.isNull(0)&&s.timestamp-it.getLong(0)<120000)return@synchronized false
        }
        val v=ContentValues().apply{put("device",s.deviceKey);put("time",s.timestamp);put("ambient",s.ambient);put("target",s.target);put("power",s.power);put("state",s.state);put("override_active",if(s.activeOverride)1 else 0);val w=weather?.takeIf{kotlin.math.abs(it.time-s.timestamp)<=5400000L};put("outdoor",w?.temperature);put("weather_place",w?.placeId)}
        db.insertWithOnConflict("measures",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }
    fun recent(key:String,days:Int):List<HobenMeasure> = synchronized(lock){
        writableDatabase.delete("measures","time<?",arrayOf((System.currentTimeMillis()-90L*86400000).toString()))
        if(key.isEmpty())return@synchronized emptyList()
        readableDatabase.rawQuery("SELECT time,ambient,target,power,state,override_active,outdoor,weather_place FROM measures WHERE device=? AND time>=? ORDER BY time",arrayOf(key,(System.currentTimeMillis()-days*86400000L).toString())).use{r->
            buildList{while(r.moveToNext())add(HobenMeasure(r.getLong(0),if(r.isNull(1))null else r.getDouble(1),if(r.isNull(2))null else r.getDouble(2),if(r.isNull(3))null else r.getInt(3),r.getInt(4),r.getInt(5)==1,if(r.isNull(6))null else r.getDouble(6),if(r.isNull(7))null else r.getString(7)))}
        }
    }
    fun clear(){synchronized(lock){writableDatabase.delete("measures",null,null)}}
    companion object{private val lock=Any()}
}
internal object HobenObservation{
    private const val NAME="hoben-history"
    private fun prefs(c:Context)=c.getSharedPreferences("hoben_observation",Context.MODE_PRIVATE)
    private val state=MutableStateFlow<HobenSnapshot?>(null)
    val live=state.asStateFlow()
    private val message=MutableStateFlow("")
    val status=message.asStateFlow()
    fun clearLive(){state.value=null;message.value=""}
    fun enabled(c:Context)=prefs(c).getBoolean("enabled",false)
    fun auto(c:Context)=prefs(c).getBoolean("auto",true)
    fun setAuto(c:Context,v:Boolean){prefs(c).edit().putBoolean("auto",v).apply()}
    fun configure(c:Context,v:Boolean){
        val enabled=v&&CredentialVault(c,"hoben").hasCredentials()
        prefs(c).edit().putBoolean("enabled",enabled).commit()
        val m=WorkManager.getInstance(c)
        if(enabled)m.enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<HobenHistoryWorker>(30,TimeUnit.MINUTES)
            .setInitialDelay(30,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build())
        else m.cancelUniqueWork(NAME)
    }
    private fun currentKey(c:Context)=CredentialVault(c,"hoben").load()?.first?.let(::hobenDeviceKey)
    fun rows(c:Context,days:Int)=HobenHistoryStore(c).use{it.recent(currentKey(c).orEmpty(),days)}
    private fun publish(c:Context,s:HobenSnapshot):Boolean{
        if(s.deviceKey.isEmpty()||s.deviceKey!=currentKey(c))return false
        val old=state.value
        if(old==null||old.deviceKey!=s.deviceKey||old.timestamp<=s.timestamp)state.value=s
        return true
    }
    fun accept(c:Context,s:HobenSnapshot,source:String){
        if(!publish(c,s)||!enabled(c))return
        val event=CollectionLog.start(c,source)
        try{CollectionLog.finish(c,event,if(HobenHistoryStore(c).use{it.record(s,HouseWeatherSource.cached(c))})"Relevé enregistré" else "Doublon ou mesure indisponible")}
        catch(_:Exception){CollectionLog.finish(c,event,"Erreur de stockage")}
    }
    fun collect(c:Context,source:String):HobenPairResult{
        val event=if(enabled(c))CollectionLog.start(c,source)else null
        var outcome="Interrompu"
        try{
            val result=HobenOverrideGateway.read(c)
            val s=(result as? HobenPairResult.Opened)?.state
            outcome=when(result){
                is HobenPairResult.Opened->result.readStatus
                is HobenPairResult.Failed->result.message
                HobenPairResult.PinRequired->"Autorisation nécessaire"
            }
            message.value=outcome
            if(s!=null&&publish(c,s)&&event!=null){
                val weather=HouseWeatherSource.refresh(c)
                outcome=if(HobenHistoryStore(c).use{it.record(s,weather)})"Relevé enregistré"else "Doublon ou mesure indisponible"
            }
            return result
        }catch(_:Exception){outcome="Lecture ou stockage indisponible";message.value=outcome;return HobenPairResult.Failed(outcome)}
        finally{if(event!=null)CollectionLog.finish(c,event,outcome)}
    }
}
class HobenHistoryWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        if(HobenObservation.enabled(applicationContext))HobenObservation.collect(applicationContext,"Hoben · arrière-plan")
        Result.success()
    }
}
@Composable
internal fun HobenMonitor(active:Boolean,onBusy:(Boolean)->Unit){
    val c=LocalContext.current
    val lifecycle=(c as? ComponentActivity)?.lifecycle
    val callback by rememberUpdatedState(onBusy)
    LaunchedEffect(active,lifecycle){
        if(active&&lifecycle!=null)lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
            while(true){
                if(HobenObservation.auto(c)&&CredentialVault(c,"hoben").hasCredentials()){
                    val recent=HobenObservation.live.value?.timestamp?:0L
                    if(System.currentTimeMillis()-recent>=110000){
                        callback(true)
                        try{withContext(Dispatchers.IO){HobenObservation.collect(c.applicationContext,"Hoben · écran")}}finally{callback(false)}
                    }
                }
                delay(120000)
            }
        }
    }
}
