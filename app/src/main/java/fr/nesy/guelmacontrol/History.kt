package fr.nesy.guelmacontrol

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal object CloudGate { val mutex = Mutex() }
internal object HistorySettings {
    private const val NAME = "guelma-history"
    private fun prefs(c: Context) = c.getSharedPreferences("history_options", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun configure(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean("enabled", enabled).commit()
        val manager = WorkManager.getInstance(c)
        if (enabled) manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HistoryWorker>(30, TimeUnit.MINUTES)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build())
        else manager.cancelUniqueWork(NAME)
    }
    fun status(c: Context, value: String) { prefs(c).edit().putString("last_status", value).putLong("last_attempt", System.currentTimeMillis()).apply() }
    fun status(c: Context) = prefs(c).getString("last_status", "Aucun relevé en arrière-plan") ?: ""
    fun lastAttempt(c: Context) = prefs(c).getLong("last_attempt", 0L)
}

class HistoryWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!HistorySettings.enabled(applicationContext)) return@withContext Result.success()
        val started = System.currentTimeMillis()
        val id = CollectionLog.start(applicationContext, "Arrière-plan", started)
        var outcome = "Interrompu"
        try {
            CloudGate.mutex.withLock {
                if (!HistorySettings.enabled(applicationContext)) { outcome = "Observation arrêtée"; return@withLock }
                val vault = CredentialVault(applicationContext)
                val saved = vault.load()
                if (saved == null) {
                    outcome = "Connexion mémorisée indisponible"
                    HistorySettings.status(applicationContext, outcome)
                    return@withLock
                }
                val api = CozytouchApi()
                try {
                    val devices = api.login(saved.first, saved.second)
                    val recorded = HistoryStore(applicationContext).use { it.record(devices) }
                    outcome = if (recorded) "Relevé enregistré" else "Pas de nouvelle mesure exploitable"
                    HistorySettings.status(applicationContext, outcome)
                } finally { api.logout() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { outcome = if (e is ApiException) e.message.orEmpty() else "Erreur réseau ou lecture"; HistorySettings.status(applicationContext, outcome) }
        finally { CollectionLog.finish(applicationContext,id,outcome) }
        // No immediate retries: keep API traffic bounded.
        Result.success()
    }
}

internal data class Sample(
    val time: Long, val showers: Int?, val t1: Double?, val t2: Double?,
    val target: Double?, val power: Double?, val energy: Long?,
    val mode: String?, val boost: String?, val absence: String?, val heating: String?, val tariff1: Long? = null, val tariff2: Long? = null
)
internal class HistoryStore(private val context: Context): SQLiteOpenHelper(context, "guelma_history.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (device TEXT NOT NULL, time INTEGER NOT NULL, showers INTEGER, t1 REAL, t2 REAL, target REAL, power REAL, energy INTEGER, mode TEXT, boost TEXT, absence TEXT, heating TEXT, tariff1 INTEGER, tariff2 INTEGER, PRIMARY KEY(device,time))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) { db.execSQL("ALTER TABLE samples ADD COLUMN tariff1 INTEGER"); db.execSQL("ALTER TABLE samples ADD COLUMN tariff2 INTEGER") } }
    private fun prune(db: SQLiteDatabase, now: Long) { db.delete("samples", "time < ?", arrayOf((now-90L*86400000).toString())) }
    fun record(devices: List<Device>): Boolean = synchronized(lock) {
        if (!HistorySettings.enabled(context)) return@synchronized false
        val heater = devices.singleOrNull { it.type == HEATER_TYPE } ?: return@synchronized false
        if (heater.available != true || heater.historyKey.isEmpty()) return@synchronized false
        val now = System.currentTimeMillis()
        val db = writableDatabase
        prune(db, now)
        db.rawQuery("SELECT MAX(time) FROM samples WHERE device=?", arrayOf(heater.historyKey)).use {
            if (it.moveToFirst() && !it.isNull(0) && now-it.getLong(0) < 120000) return@synchronized false
        }
        fun number(name: String) = heater.state(name)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        fun temp(name: String) = number(name)?.takeIf { it in 1.0..95.0 }
        val showers = heater.state("core:NumberOfShowerRemainingState")?.toIntOrNull()?.takeIf { it in 0..20 }
        val t1 = temp("modbuslink:MiddleWaterTemperatureState")
        val t2 = temp("core:MiddleWaterTemperatureInState")
        if (showers == null && t1 == null && t2 == null) return@synchronized false
        val energy = devices.singleOrNull { it.type == "modbuslink:DHWCumulatedElectricalEnergyConsumptionMBLSystemDeviceSensor" }
        val values = ContentValues().apply {
            put("device", heater.historyKey); put("time", now); put("showers", showers)
            put("t1", t1); put("t2", t2); put("target", temp("core:TargetDHWTemperatureState"))
            put("power", number("modbuslink:PowerHeatElectricalState")?.takeIf { it >= 0 })
            put("energy", energy?.takeIf { it.available == true }?.state("core:ElectricEnergyConsumptionState")?.toLongOrNull()?.takeIf { it >= 0 })
            put("tariff1", energy?.takeIf { it.available == true }?.state("core:ConsumptionTariff1State")?.toLongOrNull()?.takeIf { it >= 0 })
            put("tariff2", energy?.takeIf { it.available == true }?.state("core:ConsumptionTariff2State")?.toLongOrNull()?.takeIf { it >= 0 })
            put("mode", heater.state("modbuslink:DHWModeState")); put("boost", heater.state("modbuslink:DHWBoostModeState"))
            put("absence", heater.state("modbuslink:DHWAbsenceModeState")); put("heating", heater.state("core:HeatingStatusState"))
        }
        db.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun recent(key: String, days: Int = 14): List<Sample> = synchronized(lock) {
        if (key.isEmpty()) return@synchronized emptyList()
        val now = System.currentTimeMillis()
        prune(writableDatabase, now)
        readableDatabase.rawQuery("SELECT time,showers,t1,t2,target,power,energy,mode,boost,absence,heating,tariff1,tariff2 FROM samples WHERE device=? AND time>=? ORDER BY time",
            arrayOf(key,(now-days*86400000L).toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    fun int(i: Int) = if (c.isNull(i)) null else c.getInt(i)
                    fun double(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
                    fun long(i: Int) = if (c.isNull(i)) null else c.getLong(i)
                    fun str(i: Int) = if (c.isNull(i)) null else c.getString(i)
                    add(Sample(c.getLong(0),int(1),double(2),double(3),double(4),double(5),long(6),str(7),str(8),str(9),str(10),long(11),long(12)))
                }
            }
        }
    }
    fun clearAll() { synchronized(lock) { writableDatabase.delete("samples", null, null) } }
    companion object { private val lock = Any() }
}

internal data class HourStat(val hour: Int, val observed: Int, val likely: Int)
internal data class HabitStats(val samples: Int, val days: Int, val likelyEvents: Int, val usablePairs: Int, val weekday: List<HourStat>, val weekend: List<HourStat>)
internal fun analyzeHabits(samples: List<Sample>, zone: ZoneId = ZoneId.systemDefault()): HabitStats {
    val rows = samples.sortedBy { it.time }
    val observed = Array(2) { IntArray(24) }
    val likely = Array(2) { IntArray(24) }
    var n = 0
    for ((a,b) in rows.zipWithNext()) {
        val gap = b.time-a.time
        // Long gaps, changed settings, boost, away mode and unknown values are not evidence.
        if (gap !in 120000L..2700000L || a.showers == null || b.showers == null ||
            a.mode !in setOf("autoMode","manualEcoActive","manualEcoInactive") || a.mode != b.mode ||
            a.target == null || a.target != b.target ||
            a.boost != "off" || b.boost != "off" || a.absence != "off" || b.absence != "off") continue
        val drops = listOfNotNull(if (a.t1 != null && b.t1 != null) a.t1-b.t1 else null,
            if (a.t2 != null && b.t2 != null) a.t2-b.t2 else null)
        if (drops.isEmpty()) continue
        val time = Instant.ofEpochMilli(a.time + gap/2).atZone(zone)
        val group = if (time.dayOfWeek.value >= 6) 1 else 0
        observed[group][time.hour]++
        if (a.showers-b.showers >= 1 && drops.any { it >= 3.0 }) { likely[group][time.hour]++; n++ }
    }
    fun hours(group: Int) = (0..23).map { HourStat(it, observed[group][it], likely[group][it]) }
    return HabitStats(rows.size, rows.map { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.distinct().size,
        n, observed.sumOf { it.sum() }, hours(0), hours(1))
}
