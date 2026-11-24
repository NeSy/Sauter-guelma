package fr.nesy.guelmacontrol

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class HobenWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        val provider = manager.getAppWidgetInfo(id)?.provider
        if(id == AppWidgetManager.INVALID_APPWIDGET_ID || provider != ComponentName(this,HobenWidget::class.java)) {
            finish()
            return
        }
        val favorites = HobenFavorites.load(this)
        setContent {
            GuelmaTheme {
                Scaffold { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Widget Hoben · 1 × 1",style=MaterialTheme.typography.headlineSmall)
                        Text("Choisissez le favori de ce widget.")
                        if(favorites.isEmpty()) Text("Créez d’abord un favori dans l’espace Hoben.")
                        favorites.forEach { f ->
                            OutlinedButton(onClick={
                                HobenFavorites.bind(this@HobenWidgetConfigActivity,id,f.id)
                                HobenWidget.render(this@HobenWidgetConfigActivity,manager,id)
                                setResult(Activity.RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))
                                finish()
                            },modifier=Modifier.fillMaxWidth()) { Text(f.name+" · "+f.temperature+" · "+f.duration) }
                        }
                        TextButton(onClick={finish()}) { Text("Annuler") }
                    }
                }
            }
        }
    }
}
