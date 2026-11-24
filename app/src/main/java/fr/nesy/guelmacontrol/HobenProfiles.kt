package fr.nesy.guelmacontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class HobenProfile(val id:String,val name:String,val week:List<List<Int>>)
internal fun restoreHobenDay(current:List<Int>,saved:List<Int>):List<Int>{
    require(current.size==21&&saved.size==21)
    var next=current.toList()
    for(i in 0..6){
        val old=decodeSlot(current,i)
        val wanted=decodeSlot(saved,i)
        next=if(wanted.active)changeSlot(next,i,wanted.copy(maxPower=old.maxPower))
        else next.toMutableList().apply{this[i*3]=this[i*3] and 32767}
    }
    return next
}
internal object HobenProfileStore{
    private fun prefs(c:Context)=c.getSharedPreferences("hoben_profiles",Context.MODE_PRIVATE)
    fun key(c:Context)=CredentialVault(c,"hoben").load()?.first?.let(::hobenDeviceKey)
    fun load(c:Context,key:String):List<HobenProfile>{
        if(key.isBlank())return emptyList()
        return runCatching{
            val arr=JSONArray(prefs(c).getString(key,"[]"))
            (0 until arr.length()).mapNotNull{i->
                val o=arr.optJSONObject(i)?:return@mapNotNull null
                val w=o.optJSONArray("week")?:return@mapNotNull null
                if(w.length()!=7)return@mapNotNull null
                val week=(0..6).map{d->val a=w.getJSONArray(d);require(a.length()==21);(0..20).map{a.getInt(it).also{v->require(v in 0..65535)}}}
                HobenProfile(o.getString("id"),o.getString("name"),week)
            }
        }.getOrDefault(emptyList())
    }
    fun save(c:Context,key:String,profiles:List<HobenProfile>){
        require(key.isNotBlank())
        val a=JSONArray()
        profiles.forEach{p->val week=JSONArray();p.week.forEach{day->week.put(JSONArray(day))};a.put(JSONObject().put("id",p.id).put("name",p.name).put("week",week))}
        check(prefs(c).edit().putString(key,a.toString()).commit())
    }
}
@Composable internal fun HobenProfilesPanel(enabled:Boolean,details:HobenDetails?,ask:(HobenOverride,String)->Unit){
    val c=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var expanded by remember{mutableStateOf(false)}
    var key by remember{mutableStateOf<String?>(null)}
    var profiles by remember{mutableStateOf(emptyList<HobenProfile>())}
    var busy by remember{mutableStateOf(false)}
    var msg by remember{mutableStateOf("")}
    var capture by remember{mutableStateOf(false)}
    var name by remember{mutableStateOf("")}
    var pendingDelete by remember{mutableStateOf<HobenProfile?>(null)}
    LaunchedEffect(expanded,enabled){
        if(expanded&&!busy){
            val loaded=withContext(Dispatchers.IO){val k=HobenProfileStore.key(c);k to if(k!=null)HobenProfileStore.load(c,k)else emptyList()}
            key=loaded.first;profiles=loaded.second
        }
    }
    OutlinedButton(onClick={expanded=!expanded}){Text(if(expanded)"Masquer les sauvegardes"else "Programmes hebdomadaires sauvegardés")}
    if(expanded){
        Card(Modifier.fillMaxWidth()){
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Sauvegardes locales",style=MaterialTheme.typography.titleMedium)
                Text("Capture les 7 jours sans commande de chauffe. Restaurer une journée conserve ses limites de puissance actuelles.")
                Button(onClick={name="";capture=true},enabled=enabled&&!busy&&key!=null){Text("Sauvegarder le programme actuel")}
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(msg.isNotBlank())Text(msg,style=MaterialTheme.typography.bodySmall)
                if(profiles.isEmpty())Text("Aucun programme sauvegardé.")
                profiles.forEach{p->
                    HorizontalDivider()
                    Text(p.name,style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick={
                        val d=details?:return@TextButton
                        val proposed=runCatching{restoreHobenDay(d.slots,p.week[d.day])}.getOrNull()
                        if(proposed==null){msg="Plages sauvegardées hors des limites prises en charge.";return@TextButton}
                        val day=listOf("dimanche","lundi","mardi","mercredi","jeudi","vendredi","samedi")[d.day]
                        ask(HobenOverride.Schedule(d.day,d.slots,proposed,true),"Restaurer le $day du programme « ${p.name} » ? Seule cette journée sera écrite ; les limites de puissance actuelles restent inchangées.")
                    },enabled=enabled&&!busy&&details!=null){Text("Restaurer la journée lue")}
                    TextButton(onClick={pendingDelete=p},enabled=!busy){Text("Supprimer la sauvegarde")}
                }
                if(details==null)Text("Pour restaurer : lisez d’abord une journée dans la programmation ci-dessus.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(capture)AlertDialog(onDismissRequest={capture=false},title={Text("Sauvegarder la semaine")},text={Column{OutlinedTextField(name,{name=it.take(50)},label={Text("Nom du programme")},singleLine=true);Text("La lecture des 7 journées ne modifie pas le poêle.")}},confirmButton={Button(onClick={
        val label=name.trim();val expectedKey=key;capture=false;busy=true;msg="Lecture des 7 journées…"
        scope.launch{
            try{
                val result=withContext(Dispatchers.IO){
                    val r=HobenPairSession(c).use{it.open(null,true,detailWeek=true)}
                    val opened=r as? HobenPairResult.Opened
                    val week=opened?.week
                    val k=HobenProfileStore.key(c)
                    if(week==null||k==null||k!=expectedKey||opened?.state?.deviceKey!=k)throw IllegalStateException("Lecture incomplète ou poêle différent")
                    val next=HobenProfileStore.load(c,k)+HobenProfile(UUID.randomUUID().toString(),label,week)
                    HobenProfileStore.save(c,k,next);next
                }
                profiles=result;msg="Programme sauvegardé."
            }catch(_:Exception){msg="Sauvegarde impossible. Aucun ordre envoyé."}finally{busy=false}
        }
    },enabled=name.isNotBlank()&&!busy&&enabled){Text("Lire et sauvegarder")}},dismissButton={TextButton(onClick={capture=false}){Text("Annuler")}})
    pendingDelete?.let{p->AlertDialog(onDismissRequest={pendingDelete=null},title={Text("Supprimer cette sauvegarde ?")},text={Text(p.name)},confirmButton={TextButton(onClick={
        val k=key;pendingDelete=null
        if(k!=null)scope.launch{try{profiles=withContext(Dispatchers.IO){val next=HobenProfileStore.load(c,k).filterNot{it.id==p.id};HobenProfileStore.save(c,k,next);next}}catch(_:Exception){msg="Suppression impossible."}}
    }){Text("Supprimer")}},dismissButton={TextButton(onClick={pendingDelete=null}){Text("Annuler")}})}
}
