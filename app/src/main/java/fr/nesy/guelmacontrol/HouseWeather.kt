package fr.nesy.guelmacontrol

import android.content.Context
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock

internal data class WeatherPlace(val id:String,val label:String,val lat:Double,val lon:Double)
internal data class HouseWeather(val placeId:String,val temperature:Double,val time:Long)
internal object HouseWeatherSource{
    private fun prefs(c:Context)=c.getSharedPreferences("house_weather",Context.MODE_PRIVATE)
    private val gate=ReentrantLock()
    fun location(c:Context):WeatherPlace?{
        val p=prefs(c);val id=p.getString("id",null)?:return null
        val label=p.getString("label",null)?:return null
        val lat=p.getString("lat",null)?.toDoubleOrNull()?:return null
        val lon=p.getString("lon",null)?.toDoubleOrNull()?:return null
        if(lat !in -90.0..90.0||lon !in -180.0..180.0)return null
        return WeatherPlace(id,label,lat,lon)
    }
    fun select(c:Context,place:WeatherPlace?){
        val e=prefs(c).edit().clear()
        if(place!=null)e.putString("id",place.id).putString("label",place.label).putString("lat",place.lat.toString()).putString("lon",place.lon.toString())
        e.commit()
    }
    private fun get(url:String):JSONObject{
        val conn=URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout=5000;conn.readTimeout=5000;conn.instanceFollowRedirects=false
        try{
            if(conn.responseCode!=200)error("Weather HTTP failure")
            val out=java.io.ByteArrayOutputStream()
            conn.inputStream.use{input->val bytes=ByteArray(4096);while(true){val n=input.read(bytes);if(n<0)break;out.write(bytes,0,n);if(out.size()>262144)error("Weather response too large")}}
            return JSONObject(out.toString("UTF-8"))
        }finally{conn.disconnect()}
    }
    fun search(query:String):List<WeatherPlace>{
        val q=query.trim().take(80)
        if(q.length<2)return emptyList()
        val j=get("https://geocoding-api.open-meteo.com/v1/search?name="+URLEncoder.encode(q,"UTF-8")+"&count=8&language=fr&format=json")
        val arr=j.optJSONArray("results")?:return emptyList()
        return (0 until arr.length()).mapNotNull{i->
            val o=arr.optJSONObject(i)?:return@mapNotNull null
            val lat=o.optDouble("latitude");val lon=o.optDouble("longitude")
            val id=o.optLong("id",0L)
            val name=o.optString("name")
            if(id<=0||name.isBlank()||lat !in -90.0..90.0||lon !in -180.0..180.0)return@mapNotNull null
            WeatherPlace(id.toString(),listOf(name,o.optString("admin2"),o.optString("country")).filter{it.isNotBlank()}.distinct().joinToString(", "),lat,lon)
        }
    }
    fun cached(c:Context):HouseWeather?{
        val p=prefs(c);val place=location(c)?:return null
        if(p.getString("reading_id",null)!=place.id)return null
        val t=p.getLong("time",0L);val temp=p.getString("temperature",null)?.toDoubleOrNull()?:return null
        if(kotlin.math.abs(System.currentTimeMillis()-t)>5400000L||temp !in -80.0..65.0)return null
        return HouseWeather(place.id,temp,t)
    }
    fun refresh(c:Context):HouseWeather?{
        if(!gate.tryLock())return cached(c)
        try{
            val place=location(c)?:return null
            val p=prefs(c);val now=System.currentTimeMillis()
            val old=cached(c)
            if(old!=null&&now-p.getLong("success",0L) in 0..1800000L)return old
            if(now-p.getLong("attempt",0L) in 0..900000L)return old
            p.edit().putLong("attempt",now).apply()
            return try{
                val j=get("https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}&current=temperature_2m&temperature_unit=celsius&timeformat=unixtime&timezone=GMT")
                val cur=j.getJSONObject("current")
                val temp=cur.getDouble("temperature_2m")
                val stamp=cur.getLong("time")*1000L
                if(temp !in -80.0..65.0||kotlin.math.abs(now-stamp)>5400000L||location(c)?.id!=place.id)return null
                if(j.getJSONObject("current_units").getString("temperature_2m")!="°C")return null
                p.edit().putString("reading_id",place.id).putString("temperature",temp.toString()).putLong("time",stamp).putLong("success",now).commit()
                HouseWeather(place.id,temp,stamp)
            }catch(_:Exception){cached(c)}
        }finally{gate.unlock()}
    }
}
internal data class HobenOutdoorStat(val label:String,val rate:Double,val count:Int)
internal fun hobenOutdoorStats(rows:List<HobenMeasure>,place:String?):List<HobenOutdoorStat>{
    if(place==null)return emptyList()
    val bins=Array(3){mutableListOf<Double>()}
    for((a,b) in rows.sortedBy{it.time}.zipWithNext()){
        val hours=(b.time-a.time)/3600000.0
        if(hours !in (2.0/60)..0.75||a.weatherPlace!=place||b.weatherPlace!=place||a.outdoor==null||b.outdoor==null||a.ambient==null||b.ambient==null||a.power!=0||b.power!=0||a.overrideActive||b.overrideActive||a.target==null||a.target!=b.target)continue
        val delta=(a.ambient+b.ambient-a.outdoor-b.outdoor)/2
        val loss=(a.ambient-b.ambient)/hours
        if(delta<=0||loss !in 0.0..6.0)continue
        bins[if(delta<10)0 else if(delta<20)1 else 2].add(loss)
    }
    return bins.mapIndexedNotNull{i,values->if(values.size<8)null else HobenOutdoorStat(listOf("Écart < 10 °C","Écart 10–20 °C","Écart ≥ 20 °C")[i],values.sorted()[values.size/2],values.size)}
}
@Composable internal fun HouseWeatherPanel(){
    val c=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var place by remember{mutableStateOf(HouseWeatherSource.location(c))}
    var current by remember{mutableStateOf<HouseWeather?>(null)}
    var expanded by remember{mutableStateOf(false)}
    var query by remember{mutableStateOf("")}
    var options by remember{mutableStateOf(emptyList<WeatherPlace>())}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    LaunchedEffect(place){
        current=withContext(Dispatchers.IO){HouseWeatherSource.cached(c)}
        if(place!=null)while(true){
            current=withContext(Dispatchers.IO){HouseWeatherSource.refresh(c)}
            delay(1800000)
        }
    }
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Température extérieure",style=MaterialTheme.typography.titleMedium)
            if(place==null)Text("Facultatif : choisissez une commune. Aucun accès à la position du téléphone.")
            else{
                Text(place?.label.orEmpty())
                Text(current?.takeIf{it.placeId==place?.id}?.let{String.format(java.util.Locale.FRANCE,"%.1f °C",it.temperature)}?:"—",style=MaterialTheme.typography.headlineMedium)
                current?.let{Text("Météo modélisée à "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(it.time)),style=MaterialTheme.typography.bodySmall)}
                Text("Source : Open-Meteo · open-meteo.com",style=MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={expanded=true;query="";options=emptyList();status=""}){Text(if(place==null)"Choisir une commune"else "Changer")}
                if(place!=null)TextButton(onClick={HouseWeatherSource.select(c,null);place=null;current=null}){Text("Désactiver")}
            }
        }
    }
    if(expanded)AlertDialog(onDismissRequest={if(!busy)expanded=false},title={Text("Commune pour la météo")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Votre recherche de commune est envoyée à Open-Meteo. Aucun identifiant Hoben ou Cozytouch n’est transmis.")
            OutlinedTextField(query,{query=it.take(80);options=emptyList()},label={Text("Commune ou code postal")},singleLine=true,enabled=!busy)
            Button(onClick={val q=query;busy=true;scope.launch{try{options=withContext(Dispatchers.IO){HouseWeatherSource.search(q)};status=if(options.isEmpty())"Aucune commune trouvée."else ""}catch(_:Exception){status="Recherche indisponible."}finally{busy=false}}},enabled=!busy&&query.trim().length>=2){Text("Rechercher")}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(status.isNotBlank())Text(status)
            options.forEach{p->OutlinedButton(onClick={HouseWeatherSource.select(c,p);place=p;current=null;expanded=false},modifier=Modifier.fillMaxWidth()){Text(p.label)}}
        }
    },confirmButton={TextButton(onClick={expanded=false},enabled=!busy){Text("Fermer")}})
}
