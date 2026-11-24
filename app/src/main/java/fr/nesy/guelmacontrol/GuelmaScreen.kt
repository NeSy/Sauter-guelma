package fr.nesy.guelmacontrol

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Aqua=Color(0xFF39C9FF)
private val Ink=Color(0xFF0B1423)
private val Muted=Color(0xFF9DB6CB)
private val Amber=Color(0xFFFFC56B)
private val Mint=Color(0xFF70E2B0)
private val CardShape=RoundedCornerShape(24.dp)

@Composable
internal fun GuelmaTheme(content:@Composable ()->Unit){
    val c=LocalContext.current
    val prefs=remember{c.getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE)}
    var theme by remember{mutableStateOf(prefs.getString("theme","system")?:"system")}
    val dark=theme=="dark"||(theme=="system"&&isSystemInDarkTheme())
    val colors=if(dark) darkColorScheme(primary=Aqua,onPrimary=Ink,background=Ink,surface=Color(0xFF13243A),surfaceVariant=Color(0xFF20334A),onSurface=Color(0xFFF0F7FF),onSurfaceVariant=Muted,outline=Color(0xFF40546C))
    else lightColorScheme(primary=Color(0xFF006D98),onPrimary=Color.White,background=Color(0xFFF1F6FA),surface=Color.White,surfaceVariant=Color(0xFFE2EBF3),onSurface=Color(0xFF142C40),onSurfaceVariant=Color(0xFF51687A))
    CompositionLocalProvider(LocalAppearance provides Appearance(theme){v->theme=v;prefs.edit().putString("theme",v).apply()}){
        MaterialTheme(colorScheme=colors,content=content)
    }
}
private data class Appearance(val value:String,val update:(String)->Unit)
private val LocalAppearance=staticCompositionLocalOf{Appearance("system"){}}
private data class Nav(val name:String,val icon:ImageVector)
private data class Order(val command:String,val value:Any,val label:String)

@Composable
internal fun GuelmaScreen(model:DiagnosticModel){
    val context=LocalContext.current
    var tab by rememberSaveable{mutableStateOf(0)}
    var pending by remember{mutableStateOf<Order?>(null)}
    val nav=listOf(Nav("Accueil",Icons.Filled.Home),Nav("Planning",Icons.Filled.DateRange),Nav("Analyse",Icons.Filled.Info),Nav("Réglages",Icons.Filled.Settings))
    val heater=model.devices.singleOrNull{it.type==HEATER_TYPE}
    val enabled=model.connected&&!model.demo&&!model.busy&&!model.needsRefresh&&heater?.available==true
    val request:(String,Any,String)->Unit={cmd,value,label->if(enabled)pending=Order(cmd,value,label)}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
        if(model.connected)NavigationBar(containerColor=MaterialTheme.colorScheme.surface){
            nav.forEachIndexed{i,n->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(n.icon,null)},label={Text(n.name)})}
        }
    }){padding->
        Column(Modifier.fillMaxSize().padding(padding)){
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Color(0xFF005B9D),Aqua))),contentAlignment=Alignment.Center){WaterGlyph(Modifier.size(26.dp))}
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)){
                    Text("Guelma Control",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("Sauter Guelma 2 MP · 65 L",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick={context.startActivity(android.content.Intent(context,HobenActivity::class.java))}){Text("Hoben")}
                if(model.connected&&!model.demo)IconButton(onClick={model.refresh()},enabled=!model.busy){Icon(Icons.Filled.Refresh,"Actualiser")}
            }
            if(model.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            key(tab,model.connected){
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    if(!model.connected) LoginView(model){tab=0}
                    else {
                        if(model.demo) Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(16.dp)){
                            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text("Aperçu démo · aucune commande",Modifier.weight(1f));TextButton(onClick={model.logout();tab=0}){Text("Quitter")}}
                        }
                        if(model.actionMessage.isNotBlank()) Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer){
                            Text(model.actionMessage,Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        when(tab){
                            0->if(heater!=null) HomeView(heater,model,enabled,request,{tab=1}) else EmptyHeater()
                            1->{SectionTitle("Planning","Organisez votre réserve d’eau chaude")
                                if(heater!=null){PremiumCard{VacationControls(heater,enabled){v,l->request("setAbsenceMode",v,l)}}
                                    if(!model.demo)PredictivePanel(heater,model.lastSync,model.historyEnabled)
                                    else Text("Les recommandations apparaîtront après apprentissage.")
                                }else EmptyHeater()
                            }
                            2->{SectionTitle("Analyse","Comprendre et anticiper vos usages")
                                if(heater!=null&&!model.demo)HistoryPanel(heater,model)else Text("Les données réelles sont nécessaires pour l’analyse.")
                            }
                            else->SettingsView(model,heater){tab=0}
                        }
                    }
                    if(model.error.isNotBlank())Text(model.error,color=MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
    pending?.let{order->
        AlertDialog(onDismissRequest={pending=null},title={Text("Confirmer le changement")},text={Text(order.label+" ?")},
            confirmButton={Button(onClick={pending=null;model.send(order.command,order.value)},enabled=enabled){Text("Confirmer")}},
            dismissButton={TextButton(onClick={pending=null}){Text("Annuler")}})
    }
}
@Composable
private fun SectionTitle(title:String,subtitle:String){
    Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
    Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
}
@Composable
private fun PremiumCard(content:@Composable ColumnScope.()->Unit){
    Card(Modifier.fillMaxWidth(),shape=CardShape,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable
private fun WaterGlyph(modifier:Modifier){
    Canvas(modifier){
        val w=size.width;val h=size.height
        val p=Path().apply{moveTo(w*.5f,0f);cubicTo(w*.38f,h*.25f,w*.09f,h*.51f,w*.09f,h*.68f);cubicTo(w*.09f,h*1.11f,w*.91f,h*1.11f,w*.91f,h*.68f);cubicTo(w*.91f,h*.51f,w*.62f,h*.25f,w*.5f,0f);close()}
        drawPath(p,Color.White)
        drawLine(Aqua,Offset(w*.32f,h*.65f),Offset(w*.39f,h*.79f),strokeWidth=w*.08f)
    }
}
@Composable
private fun EmptyHeater(){PremiumCard{Text("Chauffe-eau indisponible",style=MaterialTheme.typography.titleLarge);Text("Actualisez la connexion ou consultez Réglages → Diagnostic technique.")}}
private fun Device.number(name:String)=state(name)?.toDoubleOrNull()?.takeIf{it.isFinite()}
private fun fmt(v:Double?)=v?.let{String.format(java.util.Locale.FRANCE,"%.1f",it)}?:"—"
@Composable
private fun HomeView(h:Device,model:DiagnosticModel,enabled:Boolean,send:(String,Any,String)->Unit,planning:()->Unit){
    val remaining=h.number("core:NumberOfShowerRemainingState")?.toInt()
    val max=h.number("core:MaximalShowerManualModeState")?.toInt()
    val desired=h.number("core:ExpectedNumberOfShowerState")?.toInt()
    val target=h.number("core:TargetDHWTemperatureState")?.toInt()
    val mode=h.state("modbuslink:DHWModeState")
    val boost=h.state("modbuslink:DHWBoostModeState")
    val absent=h.state("modbuslink:DHWAbsenceModeState")
    val heating=h.state("core:HeatingStatusState")
    val activeBoost=boost in setOf("on","prog")
    val absentActive=absent in setOf("on","prog")
    val ready=h.available==true
    val status=if(model.demo)"Démonstration" else if(!ready)"Indisponible" else if(model.needsRefresh)"À vérifier" else if(model.busy)"Synchronisation" else "Connecté"
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text("Mon eau chaude",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text(if(model.lastSync>0&&!model.demo)"Mis à jour à "+java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(model.lastSync)) else "Réserve et confort",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Surface(shape=CircleShape,color=if(ready&&!model.needsRefresh)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant){
            Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Box(Modifier.size(7.dp).clip(CircleShape).background(if(ready&&!model.needsRefresh)Color(0xFF219D70)else Amber))
                Text(status,style=MaterialTheme.typography.labelSmall)
            }
        }
    }
    ReserveHero(h,remaining,max,heating,activeBoost)
    PremiumCard{
        Text("Mode de fonctionnement",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(Triple("Auto","Adaptatif","autoMode"),Triple("Éco","Manuel éco","manualEcoActive"),Triple("Manuel","À la demande","manualEcoInactive")).forEach{(label,desc,value)->
                val selected=mode==value
                val can=enabled&&!selected&&validCommand(h,"setDHWMode",value)
                val shape=RoundedCornerShape(16.dp)
                Column(Modifier.weight(1f).clip(shape).background(if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .border(if(selected)1.5.dp else 0.dp,if(selected)MaterialTheme.colorScheme.primary else Color.Transparent,shape)
                    .clickable(enabled=can){send("setDHWMode",value,"Passer en mode "+label)}.padding(horizontal=6.dp,vertical=13.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Icon(if(selected)Icons.Filled.Check else if(value=="autoMode")Icons.Filled.Refresh else if(value=="manualEcoActive")Icons.Filled.Favorite else Icons.Filled.Settings,null,tint=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleSmall)
                    Text(desc,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                }
            }
        }
        if(absentActive||activeBoost)Text("Les changements de mode sont indisponibles pendant le Boost ou l’absence.",style=MaterialTheme.typography.bodySmall)
        else if(mode==null)Text("Mode non renseigné.",style=MaterialTheme.typography.bodySmall)
    }
    var edit by remember{mutableStateOf("")}
    val manual=mode=="manualEcoInactive"&&!activeBoost&&!absentActive
    val minS=h.number("core:MinimalShowerManualModeState")?.toInt()
    val minT=h.number("core:MinimalTemperatureManualModeState")?.toInt()
    val maxT=h.number("core:MaximalTemperatureManualModeState")?.toInt()
    PremiumCard{
        Text("Votre confort",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        SettingLine("Douches souhaitées",desired?.toString()?:"—",enabled&&manual&&minS!=null&&max!=null&&minS in 1..max&&max<=20&&desired!=null&&validCommand(h,"setExpectedNumberOfShower",desired)){edit="showers"}
        HorizontalDivider(color=MaterialTheme.colorScheme.surfaceVariant)
        SettingLine("Température de consigne",target?.let{"$it °C"}?:"—",enabled&&manual&&minT!=null&&maxT!=null&&minT in 40..maxT&&maxT<=80&&target!=null&&validCommand(h,"setTargetDHWTemperature",target)){edit="temperature"}
        if(!manual)Text("Passez en mode Manuel pour modifier la consigne ou les douches.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(edit=="showers"&&minS!=null&&max!=null&&desired!=null&&minS<=max)ValueDialog("Douches souhaitées","douches",desired,minS,max,{edit=""}){v->edit="";send("setExpectedNumberOfShower",v,"Régler la réserve souhaitée à "+v+" douches")}
    if(edit=="temperature"&&minT!=null&&maxT!=null&&target!=null&&minT<=maxT)ValueDialog("Température de consigne","°C",target,minT,maxT,{edit=""}){v->edit="";send("setTargetDHWTemperature",v,"Régler la température à "+v+" °C")}
    val nextBoost=if(activeBoost)"off" else "on"
    PremiumCard{
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF5F4018)),contentAlignment=Alignment.Center){Text("⚡",fontSize=26.sp,color=Amber)}
            Column(Modifier.weight(1f)){Text("Boost",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Text(if(activeBoost)"Chauffe rapide active"else "Besoin exceptionnel d’eau chaude",style=MaterialTheme.typography.bodySmall)}
            Switch(checked=activeBoost,onCheckedChange={send("setBoostMode",nextBoost,if(activeBoost)"Arrêter le Boost"else "Activer le Boost")},enabled=enabled&&boost in setOf("on","off","prog")&&validCommand(h,"setBoostMode",nextBoost))
        }
    }
    PremiumCard{
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Icon(Icons.Filled.DateRange,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(30.dp))
            Column(Modifier.weight(1f)){Text("Absence & planning",fontWeight=FontWeight.SemiBold);Text(if(absentActive)"Mode absence actif"else "Préparer votre retour",style=MaterialTheme.typography.bodySmall)}
            IconButton(onClick=planning){Icon(Icons.Filled.KeyboardArrowRight,"Ouvrir le planning")}
        }
    }
}
@Composable
private fun ReserveHero(h:Device,remaining:Int?,maximum:Int?,heating:String?,boost:Boolean){
    val fraction=if(remaining!=null&&maximum!=null&&maximum>0)(remaining.toFloat()/maximum).coerceIn(0f,1f)else 0f
    val level by animateFloatAsState(fraction,tween(750),label="réserve")
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.verticalGradient(listOf(Color(0xFF16395B),Color(0xFF0C2038),Color(0xFF10273D))))){
        Column(Modifier.padding(17.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("EAU CHAUDE DISPONIBLE",color=Muted,style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
                Surface(shape=CircleShape,color=Color(0xFF25435A)){Text(if(boost)"BOOST"else if(heating=="on")"CHAUFFE"else if(heating=="off")"EN VEILLE"else "ÉTAT INCONNU",Modifier.padding(horizontal=9.dp,vertical=6.dp),color=if(boost||heating=="on")Amber else Mint,style=MaterialTheme.typography.labelSmall)}
            }
            Box(Modifier.fillMaxWidth().height(205.dp),contentAlignment=Alignment.Center){
                Canvas(Modifier.fillMaxSize()){
                    val w=size.width*.43f;val hh=size.height*.94f;val x=(size.width-w)/2;val y=(size.height-hh)/2
                    val r=w*.19f
                    drawRoundRect(Color(0xFF24425E),Offset(x,y),Size(w,hh),CornerRadius(r))
                    val clip=Path().apply{addRoundRect(androidx.compose.ui.geometry.RoundRect(androidx.compose.ui.geometry.Rect(x+4,y+4,x+w-4,y+hh-4),CornerRadius(r-3)))}
                    clipPath(clip){
                        if(level>0f){
                            val waterY=y+hh*(1-level)
                            val p=Path().apply{moveTo(x,waterY);quadraticBezierTo(x+w*.25f,waterY-13,x+w*.5f,waterY);quadraticBezierTo(x+w*.75f,waterY+13,x+w,waterY);lineTo(x+w,y+hh);lineTo(x,y+hh);close()}
                            drawPath(p,Brush.verticalGradient(listOf(Color(0xFF43D5FF),Color(0xFF1260C6)),startY=y,endY=y+hh))
                        }
                        drawLine(Color.White.copy(alpha=.17f),Offset(x+w*.5f,y),Offset(x+w*.5f,y+hh),strokeWidth=2f)
                    }
                    drawRoundRect(Color(0xFF7CB8D8),Offset(x,y),Size(w,hh),CornerRadius(r),style=Stroke(2f))
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Text(remaining?.toString()?:"—",fontSize=52.sp,fontWeight=FontWeight.Bold,color=Color.White,lineHeight=55.sp)
                    Text("douches",color=Color.White,style=MaterialTheme.typography.titleSmall)
                    Text("restantes",color=Color.White,style=MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(28.dp)){
                        HeroMetric(fmt(h.number("modbuslink:MiddleWaterTemperatureState"))+"°","Sonde 1")
                        HeroMetric(h.number("core:TargetDHWTemperatureState")?.toInt()?.let{"$it°"}?:"—","Consigne")
                    }
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(28.dp)){
                        HeroMetric(fmt(h.number("core:MiddleWaterTemperatureInState"))+"°","Sonde 2")
                        HeroMetric(h.number("modbuslink:PowerHeatElectricalState")?.toInt()?.let{"$it W"}?:"—","Puissance")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){
                repeat((maximum?:0).coerceIn(0,20)){i->Box(Modifier.padding(horizontal=3.dp).size(10.dp).clip(CircleShape).background(if(i<(remaining?:0))Aqua else Color(0xFF36516A)))}
            }
            Text("Réserve estimée · illustration globale des deux cuves",style=MaterialTheme.typography.labelSmall,color=Muted)
        }
    }
}
@Composable
private fun HeroMetric(value:String,label:String){
    Column(horizontalAlignment=Alignment.CenterHorizontally){Text(value,color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=20.sp);Text(label,color=Muted,style=MaterialTheme.typography.labelSmall)}
}
@Composable
private fun SettingLine(label:String,value:String,enabled:Boolean,action:()->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled=enabled,onClick=action).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text(value,style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
        if(enabled)Icon(Icons.Filled.KeyboardArrowRight,"Modifier",tint=MaterialTheme.colorScheme.primary)
    }
}
@Composable
private fun ValueDialog(title:String,unit:String,current:Int,min:Int,max:Int,dismiss:()->Unit,apply:(Int)->Unit){
    var value by remember{mutableStateOf(current.coerceIn(min,max))}
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("$value $unit",style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.primary)
            if(max>min)Slider(value=value.toFloat(),onValueChange={value=it.roundToInt()},valueRange=min.toFloat()..max.toFloat(),steps=(max-min-1).coerceAtLeast(0))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(min.toString());Text(max.toString())}
            if(unit=="°C")Text("Respectez les recommandations sanitaires du fabricant.",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={Button(onClick={apply(value)},enabled=value!=current){Text("Appliquer")}},dismissButton={TextButton(onClick=dismiss){Text("Annuler")}})
}
@Composable
private fun LoginView(model:DiagnosticModel,onConnect:()->Unit){
    var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var rememberMe by remember{mutableStateOf(true)}
    SectionTitle("Bienvenue","Votre chauffe-eau, en toute simplicité.")
    PremiumCard{
        if(model.remembered){Button(onClick={onConnect();model.reconnect()},enabled=!model.busy,modifier=Modifier.fillMaxWidth()){Text("Reprendre ma connexion")}}
        OutlinedTextField(email,{email=it},label={Text("E-mail Cozytouch")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!model.busy)
        OutlinedTextField(password,{password=it},label={Text("Mot de passe")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),enabled=!model.busy)
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(rememberMe,{rememberMe=it},enabled=!model.busy);Text("Mémoriser ma connexion")}
        Button(onClick={onConnect();model.login(email,password,rememberMe);password=""},modifier=Modifier.fillMaxWidth(),enabled=!model.busy&&email.isNotBlank()&&password.isNotEmpty()){Text("Se connecter")}
        Text("Identifiants chiffrés localement si mémorisés.",style=MaterialTheme.typography.bodySmall)
        if(model.remembered)TextButton(onClick={model.forget()},enabled=!model.busy){Text("Oublier la connexion")}
    }
    TextButton(onClick={onConnect();model.showDemo()},enabled=!model.busy){Text("Découvrir l’interface sans connexion")}
}
@Composable
private fun SettingsView(model:DiagnosticModel,heater:Device?,onLogout:()->Unit){
    val appearance=LocalAppearance.current
    val clipboard=LocalClipboardManager.current
    var technical by rememberSaveable{mutableStateOf(false)}
    var tariffOpen by rememberSaveable{mutableStateOf(false)}
    var logout by remember{mutableStateOf(false)}
    SectionTitle("Réglages","Personnalisez Guelma Control")
    PremiumCard{
        Text("Apparence",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("system" to "Système","light" to "Clair","dark" to "Sombre").forEach{(v,label)->FilterChip(selected=appearance.value==v,onClick={appearance.update(v)},label={Text(label)})}}
    }
    PremiumCard{
        Text("Énergie",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        SettingLine("Tarifs électriques",if(tariffOpen)"−"else "+",true){tariffOpen=!tariffOpen}
        if(tariffOpen)TariffSettingsPanel()
    }
    PremiumCard{
        Text("Compte Cozytouch",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Text(if(model.remembered)"Connexion mémorisée sur ce téléphone"else "Connexion non mémorisée")
        OutlinedButton(onClick={logout=true},enabled=!model.busy){Text("Se déconnecter")}
    }
    PremiumCard{
        SettingLine("Diagnostic technique",if(technical)"−"else "+",true){technical=!technical}
        if(technical){
            Text("Outils de maintenance · lecture seule",style=MaterialTheme.typography.bodySmall)
            Text(model.summary,style=MaterialTheme.typography.bodySmall)
            CollectionLogPanel()
            OutlinedButton(onClick={clipboard.setText(AnnotatedString(diagnosticReport(model.devices,model.demo,model.summary)))}){Text("Copier le rapport technique")}
            model.devices.forEachIndexed{i,d->
                Text("Composant "+(i+1)+" · "+d.type,style=MaterialTheme.typography.labelMedium)
                d.states.forEach{(n,v)->Text(n+" = "+v,style=MaterialTheme.typography.bodySmall)}
                if(d.commands.isNotEmpty())Text("Commandes déclarées : "+d.commands.joinToString(", "),style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
    Text("Guelma Control 0.7.0 · Application indépendante",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(logout)AlertDialog(onDismissRequest={logout=false},title={Text("Se déconnecter ?")},text={Text("La connexion mémorisée sera effacée et l’observation arrêtée. L’historique reste sur ce téléphone.")},confirmButton={TextButton(onClick={logout=false;model.logout();onLogout()}){Text("Déconnexion")}},dismissButton={TextButton(onClick={logout=false}){Text("Annuler")}})
}
internal fun demoDevices():List<Device> = listOf(Device(-1,-1,true,emptyList(),"Overkiz",HEATER_TYPE,listOf(
    "core:NumberOfShowerRemainingState" to "4","core:MaximalShowerManualModeState" to "5","core:MinimalShowerManualModeState" to "1","core:ExpectedNumberOfShowerState" to "5","core:TargetDHWTemperatureState" to "69","core:MinimalTemperatureManualModeState" to "50","core:MaximalTemperatureManualModeState" to "69","modbuslink:MiddleWaterTemperatureState" to "69.5","core:MiddleWaterTemperatureInState" to "63.4","modbuslink:PowerHeatElectricalState" to "0","core:HeatingStatusState" to "off","modbuslink:DHWModeState" to "manualEcoInactive","modbuslink:DHWBoostModeState" to "off","modbuslink:DHWAbsenceModeState" to "off"
)))
