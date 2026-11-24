package fr.nesy.guelmacontrol

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.roundToInt

internal data class HobenFavorite(val id:String,val name:String,val halfDegrees:Int,val minutes:Int){
    val compactTemperature:String get()=if(halfDegrees%2==0)"${halfDegrees/2}°" else String.format(java.util.Locale.FRANCE,"%.1f°",halfDegrees/2.0)
    val compactDuration:String get()=if(minutes<60)"${minutes}m" else if(minutes%60==0)"${minutes/60}h" else "${minutes/60}h${(minutes%60).toString().padStart(2,'0')}"
    val temperature:String get()=String.format(java.util.Locale.FRANCE,"%.1f °C",halfDegrees/2.0)
    val duration:String get()=if(minutes<60)"$minutes min"else if(minutes%60==0)"${minutes/60} h"else "${minutes/60} h ${minutes%60} min"
}
internal object HobenFavorites{
    private fun prefs(c:Context)=c.getSharedPreferences("hoben_favorites",Context.MODE_PRIVATE)
    fun load(c:Context):List<HobenFavorite>{
        val raw=prefs(c).getString("list",null)?:return listOf(HobenFavorite("default","Coup de chaud",44,120))
        return runCatching{
            val a=JSONArray(raw)
            (0 until a.length()).mapNotNull{i->
                val o=a.optJSONObject(i)?:return@mapNotNull null
                val f=HobenFavorite(o.optString("id"),o.optString("name"),o.optInt("temp"),o.optInt("minutes"))
                f.takeIf{it.id.isNotBlank()&&it.name.isNotBlank()&&it.halfDegrees in 30..50&&it.minutes in 15..480}
            }
        }.getOrDefault(emptyList())
    }
    fun save(c:Context,list:List<HobenFavorite>){
        val a=JSONArray()
        list.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("temp",it.halfDegrees).put("minutes",it.minutes))}
        prefs(c).edit().putString("list",a.toString()).apply()
        HobenWidget.refresh(c)
    }
    fun selected(c:Context)=prefs(c).getString("selected","default")
    fun select(c:Context,id:String){prefs(c).edit().putString("selected",id).apply();HobenWidget.refresh(c)}
    fun primary(c:Context):HobenFavorite?=load(c).let{rows->rows.firstOrNull{it.id==selected(c)}?:rows.firstOrNull()}
    fun bind(c:Context,widgetId:Int,favoriteId:String){prefs(c).edit().putString("widget_$widgetId",favoriteId).apply()}
    fun unbind(c:Context,widgetId:Int){prefs(c).edit().remove("widget_$widgetId").apply()}
    fun forWidget(c:Context,widgetId:Int):HobenFavorite? {
        val id=prefs(c).getString("widget_$widgetId",null) ?: return primary(c)
        return load(c).firstOrNull{it.id==id}
    }
}
internal object HobenTransport{
    // Transport only. No authentication, Modbus frame or control command is sent.
    fun probe():String{
        return try{
            Socket().use{raw->
                raw.connect(InetSocketAddress("myhoben.fr",465),5000)
                raw.soTimeout=5000
                val factory=SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw,"myhoben.fr",465,true) as SSLSocket).use{socket->
                    socket.soTimeout=5000
                    val parameters=socket.sslParameters
                    parameters.endpointIdentificationAlgorithm="HTTPS"
                    socket.sslParameters=parameters
                    socket.startHandshake()
                    "Transport TLS établi ("+socket.session.protocol+"). Poêle non authentifié."
                }
            }
        }catch(_:javax.net.ssl.SSLException){"TLS non validé : aucun contournement du certificat."}
        catch(_:java.net.SocketTimeoutException){"Délai de connexion dépassé."}
        catch(_:Exception){"Serveur inaccessible ou transport indisponible."}
    }
}
class HobenWidget:AppWidgetProvider(){
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){ids.forEach{render(context,manager,it)}}
    override fun onDeleted(context:Context,appWidgetIds:IntArray){appWidgetIds.forEach{HobenFavorites.unbind(context,it)}}
    companion object{
        internal fun render(c:Context,m:AppWidgetManager,id:Int){
            val f=HobenFavorites.forWidget(c,id)
            val views=RemoteViews(c.packageName,R.layout.hoben_widget)
            views.setTextViewText(R.id.hoben_widget_value,f?.compactTemperature?:"—")
            views.setTextViewText(R.id.hoben_widget_duration,f?.compactDuration?:"Choisir")
            val intent=if(f!=null) Intent(c,HobenActivity::class.java).putExtra("favorite",f.id) else Intent(c,HobenWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
            val pending=PendingIntent.getActivity(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.hoben_widget_root,pending)
            m.updateAppWidget(id,views)
        }
        internal fun refresh(c:Context){
            val m=AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c,HobenWidget::class.java)).forEach{render(c,m,it)}
        }
    }
}
class HobenActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val favorite=intent.getStringExtra("favorite")
        setContent{GuelmaTheme{HobenView(favorite){finish()}}}
    }
}
@Composable
private fun HobenView(initial:String?,back:()->Unit){
    val c=LocalContext.current
    var list by remember{mutableStateOf(HobenFavorites.load(c))}
    var selected by remember{mutableStateOf(HobenFavorites.primary(c)?.id)}
    var editing by remember{mutableStateOf<HobenFavorite?>(null)}
    var details by remember{mutableStateOf(list.firstOrNull{it.id==initial})}
    var remove by remember{mutableStateOf<HobenFavorite?>(null)}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("Non testé")}
    var snapshot by remember{mutableStateOf<HobenSnapshot?>(null)}
    val scope=rememberCoroutineScope()
    val flame=Color(0xFFE58A43)
    Scaffold(containerColor=MaterialTheme.colorScheme.background){padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            TextButton(onClick=back){Text("‹  Eau chaude")}
            Text("Hoben H5",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            Surface(shape=RoundedCornerShape(24.dp),color=Color(0xFF39291F)){
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                    Text("CHAUFFAGE · GRANULÉS",color=flame,style=MaterialTheme.typography.labelMedium)
                    val reading=snapshot
                    if(reading==null){
                        Text("En attente d’une lecture",color=Color.White,style=MaterialTheme.typography.titleLarge)
                        Text("Connectez votre poêle ci-dessous. Favoris locaux, commandes verrouillées.",color=Color(0xFFF3DCCB),style=MaterialTheme.typography.bodyMedium)
                    }else{
                        Text(reading.ambient?.let{String.format(java.util.Locale.FRANCE,"%.1f °C",it)}?:"—",color=Color.White,style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Bold)
                        Text("Température ambiante · consigne "+(reading.target?.let{String.format(java.util.Locale.FRANCE,"%.1f °C",it)}?:"—"),color=Color(0xFFF3DCCB))
                        Text(reading.stateLabel+" · "+reading.modeLabel,color=Color(0xFFF3DCCB))
                        Text("Puissance "+(reading.power?.let{"$it %"}?:"—")+" · Ventilation "+reading.ventLabel,color=Color(0xFFF3DCCB))
                        Text(if(reading.activeOverride)"Dérogation active"else if(reading.scheduledOverride)"Dérogation programmée"else "Pas de dérogation signalée",color=Color(0xFFF3DCCB))
                        if(reading.activeOverride||reading.scheduledOverride)Text("Dérogation : "+(reading.overrideTemp?.let{String.format(java.util.Locale.FRANCE,"%.1f °C",it)}?:"—")+" · durée remontée "+reading.overrideMinutes+" min",color=Color(0xFFF3DCCB))
                        if(reading.faults!=0)Text("Défaut signalé : consultez MyHOBEN ou le poêle.",color=Color(0xFFFFC28C))
                        Text("Lecture ponctuelle à "+java.text.DateFormat.getTimeInstance().format(java.util.Date(reading.timestamp)),color=Color(0xFFF3DCCB),style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("Mes favoris",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                TextButton(onClick={editing=HobenFavorite(UUID.randomUUID().toString(),"",44,120)}){Text("+ Ajouter")}
            }
            list.chunked(2).forEach{pair->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    pair.forEach{f->
                        Card(modifier=Modifier.weight(1f),shape=RoundedCornerShape(20.dp),onClick={details=f}){
                            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                Text(f.name,style=MaterialTheme.typography.titleSmall,maxLines=2)
                                Text(f.temperature,color=flame,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                                Text(f.duration,style=MaterialTheme.typography.bodyMedium)
                                if(selected==f.id)Text("★ Widget",color=flame,style=MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if(pair.size==1)Spacer(Modifier.weight(1f))
                }
            }
            if(list.isEmpty())Text("Ajoutez votre première dérogation favorite.")
            HobenPairingPanel{snapshot=it}
            Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Widgets Android · 1 × 1",style=MaterialTheme.typography.titleMedium)
                    Text("Ajoutez plusieurs widgets depuis le sélecteur Android, puis choisissez un favori par widget. Un appui ouvre le favori ; le pilotage reste verrouillé. Le favori étoilé est le choix par défaut.")
                    val manager=AppWidgetManager.getInstance(c)
                    Button(onClick={manager.requestPinAppWidget(ComponentName(c,HobenWidget::class.java),null,null)},enabled=manager.isRequestPinAppWidgetSupported&&list.isNotEmpty()){Text("Ajouter le widget")}
                }
            }
            Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Diagnostic réseau",style=MaterialTheme.typography.titleMedium)
                    Text("Test réseau sécurisé uniquement : myhoben.fr, port 465. Aucun identifiant demandé.")
                    Text(status,style=MaterialTheme.typography.bodySmall)
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick={busy=true;scope.launch{try{status=withContext(Dispatchers.IO){HobenTransport.probe()}}finally{busy=false}}},enabled=!busy){Text("Tester le transport TLS")}
                }
            }
        }
    }
    details?.let{f->
        AlertDialog(onDismissRequest={details=null},title={Text(f.name)},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(f.temperature+" · "+f.duration,style=MaterialTheme.typography.headlineSmall)
                Text("Activation verrouillée : la communication avec le H5 n’est pas encore validée.")
                OutlinedButton(onClick={HobenFavorites.select(c,f.id);selected=f.id;details=null}){Text("Choisir par défaut")}
                TextButton(onClick={details=null;editing=f}){Text("Modifier")}
                TextButton(onClick={details=null;remove=f}){Text("Supprimer")}
            }
        },confirmButton={TextButton(onClick={details=null}){Text("Fermer")}})
    }
    editing?.let{f->
        var name by remember(f.id){mutableStateOf(f.name)}
        var temp by remember(f.id){mutableStateOf(f.halfDegrees)}
        var minutes by remember(f.id){mutableStateOf(f.minutes)}
        AlertDialog(onDismissRequest={editing=null},title={Text("Dérogation favorite")},text={
            Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
                OutlinedTextField(name,{name=it.take(40)},label={Text("Nom")},singleLine=true)
                Text(String.format(java.util.Locale.FRANCE,"%.1f °C",temp/2.0),style=MaterialTheme.typography.titleLarge)
                Slider(value=temp.toFloat(),onValueChange={temp=it.roundToInt()},valueRange=30f..50f,steps=19)
                Text("Durée : "+HobenFavorite("",name,temp,minutes).duration)
                Slider(value=(minutes/15).toFloat(),onValueChange={minutes=it.roundToInt()*15},valueRange=1f..32f,steps=30)
                Text("Valeurs locales proposées, à valider avec le poêle avant utilisation.",style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={Button(onClick={
            val updated=f.copy(name=name.trim(),halfDegrees=temp,minutes=minutes)
            list=if(list.any{it.id==f.id})list.map{if(it.id==f.id)updated else it}else list+updated
            HobenFavorites.save(c,list);selected=HobenFavorites.primary(c)?.id;editing=null
        },enabled=name.isNotBlank()){Text("Enregistrer")}},dismissButton={TextButton(onClick={editing=null}){Text("Annuler")}})
    }
    remove?.let{f->AlertDialog(onDismissRequest={remove=null},title={Text("Supprimer ce favori ?")},text={Text(f.name)},confirmButton={TextButton(onClick={list=list.filterNot{it.id==f.id};HobenFavorites.save(c,list);selected=HobenFavorites.primary(c)?.id;remove=null}){Text("Supprimer")}},dismissButton={TextButton(onClick={remove=null}){Text("Annuler")}})}
}
