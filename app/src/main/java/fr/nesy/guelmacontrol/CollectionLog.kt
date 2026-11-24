package fr.nesy.guelmacontrol

import android.content.Context
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal object CollectionLog {
    private fun prefs(c:Context)=c.getSharedPreferences("collection_log",Context.MODE_PRIVATE)
    private fun read(c:Context):JSONArray = try { JSONArray(prefs(c).getString("events","[]")) } catch(_:Exception){JSONArray()}
    @Synchronized fun start(c:Context,source:String,time:Long=System.currentTimeMillis()):String {
        val id=UUID.randomUUID().toString()
        val old=read(c); val next=JSONArray()
        for(i in maxOf(0,old.length()-999) until old.length()) {
            val entry=old.optJSONObject(i) ?: continue
            if(entry.optLong("start")>=time-14L*86400000) next.put(entry)
        }
        next.put(JSONObject().put("id",id).put("source",source).put("start",time).put("result","En cours"))
        prefs(c).edit().putString("events",next.toString()).commit()
        return id
    }
    @Synchronized fun finish(c:Context,id:String,result:String) {
        val a=read(c)
        for(i in 0 until a.length()) {
            val o=a.optJSONObject(i) ?: continue
            if(o.optString("id")==id) { o.put("end",System.currentTimeMillis()).put("result",result.take(180));break }
        }
        prefs(c).edit().putString("events",a.toString()).commit()
    }
    @Synchronized fun lines(c:Context):List<String>{
        val a=read(c); val fmt=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM)
        return (0 until a.length()).reversed().mapNotNull{ i->
            val o=a.optJSONObject(i) ?: return@mapNotNull null
            val start=o.optLong("start"); if (start < System.currentTimeMillis()-14L*86400000) return@mapNotNull null; val end=o.optLong("end")
            val result=if(end==0L && System.currentTimeMillis()-start>180000) "Interrompu ou non terminé" else o.optString("result")
            fmt.format(Date(start))+" · "+o.optString("source")+" · "+result+
                if(end>0) " ("+((end-start)/1000).coerceAtLeast(0)+" s)" else ""
        }
    }
    @Synchronized fun clear(c:Context){prefs(c).edit().clear().commit()}
}
internal fun loggedRecord(c:Context,devices:List<Device>,source:String):Boolean {
    if(!HistorySettings.enabled(c)) return false
    val id=CollectionLog.start(c,source)
    return try {
        val ok=HistoryStore(c).use{it.record(devices)}
        CollectionLog.finish(c,id,if(ok) "Relevé enregistré" else "Aucun nouveau relevé (doublon ou données indisponibles)")
        ok
    } catch(e:Exception){CollectionLog.finish(c,id,"Erreur de stockage");throw e}
}
internal fun loggedFetch(c:Context,source:String,fetch:()->List<Device>):List<Device> {
    if (!HistorySettings.enabled(c)) return fetch()
    val id=CollectionLog.start(c,source)
    var outcome="Interrompu"
    try {
        val rows=fetch()
        outcome=try {
            val recorded=HistoryStore(c).use{it.record(rows)}
            if(recorded) "Relevé enregistré" else "Aucun nouveau relevé (doublon ou données indisponibles)"
        } catch(_:Exception) {"Erreur de stockage"}
        return rows
    } catch(e:Exception) {
        outcome=if(e is ApiException) e.message.orEmpty() else "Erreur réseau ou lecture"
        throw e
    } finally {CollectionLog.finish(c,id,outcome)}
}
@Composable
internal fun CollectionLogPanel(){
    val c=LocalContext.current.applicationContext
    val clipboard=LocalClipboardManager.current
    var expanded by remember{mutableStateOf(false)}
    var lines by remember{mutableStateOf(emptyList<String>())}
    var confirm by remember{mutableStateOf(false)}
    var schedule by remember{mutableStateOf("")}
    LaunchedEffect(expanded){
        if(expanded) while(true){
            lines=withContext(Dispatchers.IO){CollectionLog.lines(c)}
            schedule=withContext(Dispatchers.IO){
                try {
                    val jobs=WorkManager.getInstance(c).getWorkInfosForUniqueWork("guelma-history").get(5,java.util.concurrent.TimeUnit.SECONDS)
                    val work=jobs.firstOrNull{!it.state.isFinished}
                    if(work==null) "Aucun travail périodique planifié"
                    else {
                        val next=work.nextScheduleTimeMillis
                        "Android : "+work.state.name + if(next in 1L until Long.MAX_VALUE) " · éligible à partir de "+DateFormat.getDateTimeInstance().format(Date(next)) else ""
                    }
                } catch(_:Exception) {"État de la planification indisponible"}
            }
            delay(5000)
        }
    }
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Journal de collecte · diagnostic temporaire",style=MaterialTheme.typography.titleMedium)
            Text("Derniers 1 000 événements, 14 jours maximum lors des nouveaux relevés. Aucun identifiant ni jeton.")
            OutlinedButton(onClick={expanded=!expanded}){Text(if(expanded)"Masquer le journal" else "Afficher le journal")}
            if(expanded){
                Text(if(HistorySettings.enabled(c))"Observation active · passage visé toutes les 30 min (horaire non garanti)" else "Observation désactivée")
                if(schedule.isNotBlank()) Text(schedule,style=MaterialTheme.typography.bodySmall)
                if(lines.isEmpty()) Text("Aucun événement enregistré.")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={clipboard.setText(AnnotatedString(lines.joinToString("\n")))},enabled=lines.isNotEmpty()){Text("Copier tout")}
                    TextButton(onClick={confirm=true},enabled=lines.isNotEmpty()){Text("Effacer")}
                }
                lines.take(100).forEach{Text(it,style=MaterialTheme.typography.bodySmall);HorizontalDivider()}
                if(lines.size>100)Text("100 derniers événements affichés ; Copier tout exporte le journal complet.")
            }
        }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Effacer le journal ?")},text={Text("Les mesures historiques seront conservées.")},confirmButton={TextButton(onClick={CollectionLog.clear(c);lines=emptyList();confirm=false}){Text("Effacer")}},dismissButton={TextButton(onClick={confirm=false}){Text("Annuler")}})
}
