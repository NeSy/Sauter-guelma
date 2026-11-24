package fr.nesy.guelmacontrol

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HobenQuickActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val model=ViewModelProvider(this)[HobenQuickModel::class.java]
        model.start(intent,savedInstanceState!=null)
        setContent{
            GuelmaTheme{
                BackHandler(enabled=model.running){}
                LaunchedEffect(model.confirmed){
                    if(model.confirmed){delay(1800);finish()}
                }
                Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface){
                    Column(Modifier.widthIn(max=340.dp).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text("Hoben H5",style=MaterialTheme.typography.titleMedium)
                        Text(model.title,style=MaterialTheme.typography.headlineSmall)
                        if(model.running)LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(model.message,color=if(model.confirmed)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        if(!model.running){
                            if(!model.confirmed)Button(onClick={startActivity(Intent(this@HobenQuickActivity,HobenActivity::class.java));finish()},modifier=Modifier.fillMaxWidth()){Text("Ouvrir Hoben")}
                            TextButton(onClick={finish()},modifier=Modifier.fillMaxWidth()){Text("Fermer")}
                        }
                    }
                }
            }
        }
    }
}
class HobenQuickModel(application:Application):AndroidViewModel(application){
    private var started=false
    var running by mutableStateOf(false)
        private set
    var confirmed by mutableStateOf(false)
        private set
    var title by mutableStateOf("Dérogation favorite")
        private set
    var message by mutableStateOf("Vérification…")
        private set

    fun start(intent:Intent,recreated:Boolean){
        if(started)return
        started=true
        if(recreated){message="Session interrompue. Aucun ordre répété ; relisez le poêle.";return}
        val c=getApplication<Application>()
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val provider=AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider
        if(provider!=ComponentName(c,HobenWidget::class.java)||!HobenFavorites.direct(c)){
            message="Raccourci indisponible. Aucun ordre envoyé.";return
        }
        val f=HobenFavorites.forWidget(c,id)
        if(f==null||f.id!=intent.getStringExtra("favorite")||
            f.halfDegrees!=intent.getIntExtra("temperature",-1)||f.minutes!=intent.getIntExtra("minutes",-1)){
            message="Favori modifié ou supprimé. Aucun ordre envoyé.";HobenWidget.refresh(c);return
        }
        title=f.temperature+" · "+f.duration
        if(!CredentialVault(c,"hoben").hasCredentials()){
            message="Appairage à mémoriser dans Hoben. Aucun ordre envoyé.";return
        }
        running=true
        message="Lecture du poêle, puis demande de dérogation…"
        viewModelScope.launch{
            try{
                val result=withContext(Dispatchers.IO){HobenOverrideGateway.execute(c,HobenOverride.Start(f.halfDegrees,f.minutes))}
                message=when(result){
                    is HobenPairResult.Opened->result.readStatus
                    HobenPairResult.PinRequired->"Autorisation nécessaire. Ouvrez Hoben."
                    is HobenPairResult.Failed->result.message
                }
                confirmed=result is HobenPairResult.Opened&&result.state!=null&&result.readStatus=="Dérogation confirmée par le poêle."
            }finally{running=false}
        }
    }
}
