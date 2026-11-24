package fr.nesy.guelmacontrol
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

internal data class Cell(val observed: Int = 0, val draws: Int = 0)
internal data class Interval(val a: Sample, val b: Sample, val hours: Double, val draw: Boolean)
internal fun intervals(rows: List<Sample>): List<Interval> = rows.sortedBy { it.time }.zipWithNext().mapNotNull { (a,b) ->
    val gap=b.time-a.time
    if(gap !in 120000L..2700000L) return@mapNotNull null
    val stable=a.mode in setOf("autoMode","manualEcoActive","manualEcoInactive") && a.mode==b.mode && a.target!=null && a.target==b.target &&
        a.boost=="off" && b.boost=="off" && a.absence=="off" && b.absence=="off" && a.showers!=null && b.showers!=null
    val drops=listOfNotNull(a.t1?.let { x -> b.t1?.let { x-it } },a.t2?.let { x -> b.t2?.let { x-it } })
    if(drops.isEmpty() || !stable) return@mapNotNull null
    Interval(a,b,gap/3600000.0,a.showers!!-b.showers!!>=1 && drops.any { it>=3.0 })
}
internal fun weeklyMap(rows: List<Sample>, zone: ZoneId=ZoneId.systemDefault()): List<List<Cell>> {
    val grid=Array(7){ Array(24){ Cell() } }
    intervals(rows).forEach { i ->
        val t=Instant.ofEpochMilli(i.a.time+(i.b.time-i.a.time)/2).atZone(zone)
        val d=t.dayOfWeek.value-1; val h=t.hour; val c=grid[d][h]
        grid[d][h]=Cell(c.observed+1,c.draws+if(i.draw) 1 else 0)
    }
    return grid.map { it.toList() }
}
internal data class ThermalEstimate(val cooling: Double?,val heating: Double?,val coolingCount:Int,val heatingCount:Int,val coveredHours:Double,val probableDraws:Int)
internal fun median(values: List<Double>):Double? {
    if(values.isEmpty()) return null
    val s=values.sorted(); return (s[(s.size-1)/2]+s[s.size/2])/2
}
internal fun thermalModel(rows:List<Sample>): ThermalEstimate {
    val pairs=intervals(rows)
    val cool=mutableListOf<Double>(); val heat=mutableListOf<Double>()
    pairs.forEach { i ->
        val a=i.a; val b=i.b
        val t1=a.t1; val t2=a.t2; val u1=b.t1; val u2=b.t2
        if(t1!=null && t2!=null && u1!=null && u2!=null && !i.draw) {
            val rate=((u1+u2)-(t1+t2))/2/i.hours
            if(a.heating=="off" && b.heating=="off" && a.power==0.0 && b.power==0.0 &&
                a.showers==b.showers && rate in -3.0..0.0 && abs(u1-t1)<3 && abs(u2-t2)<3) cool.add(-rate)
            if(a.power!=null && b.power!=null && a.power>0 && b.power>0 && rate in 0.5..50.0 && b.showers!!>=a.showers!!) heat.add(rate)
        }
    }
    return ThermalEstimate(median(cool).takeIf { cool.size>=12 },median(heat).takeIf { heat.size>=8 },cool.size,heat.size,pairs.sumOf{it.hours},pairs.count{it.draw})
}
internal data class EnergyInterval(val time:Long,val delta:Long,val t1:Long?,val t2:Long?)
internal fun energyIntervals(rows:List<Sample>):List<EnergyInterval> = rows.sortedBy{it.time}.zipWithNext().mapNotNull { (a,b) ->
    val start=a.energy ?: return@mapNotNull null; val end=b.energy ?: return@mapNotNull null
    if(b.time-a.time !in 120000L..3600000L || end<start) return@mapNotNull null
    fun delta(x:Long?,y:Long?)=if(x!=null && y!=null && y>=x) y-x else null
    EnergyInterval(b.time,end-start,delta(a.tariff1,b.tariff1),delta(a.tariff2,b.tariff2))
}
internal fun csv(rows:List<Sample>):String=buildString {
    appendLine("timestamp_utc,showers_estimated,temp1_c,temp2_c,target_c,power_raw,energy_raw,tariff1_raw,tariff2_raw,mode,boost,absence,heating")
    rows.forEach { r -> appendLine(listOf(Instant.ofEpochMilli(r.time),r.showers,r.t1,r.t2,r.target,r.power,r.energy,r.tariff1,r.tariff2,r.mode,r.boost,r.absence,r.heating).joinToString(","){it?.toString()?:""}) }
}

internal data class DemandWindow(val hour:Int,val days:Int,val demandDays:Int,val reserveLoss:Int)
internal data class Forecast(val days:Int,val coverage:Double,val windows:List<DemandWindow>)
internal fun demandForecast(rows:List<Sample>,weekend:Boolean,zone:ZoneId=ZoneId.systemDefault()):Forecast {
    val pairs=intervals(rows)
    val dayHours=mutableMapOf<Pair<java.time.LocalDate,Int>,Double>()
    val losses=mutableMapOf<Pair<java.time.LocalDate,Int>,Int>()
    for(i in pairs) {
        val t=Instant.ofEpochMilli(i.a.time+(i.b.time-i.a.time)/2).atZone(zone)
        val key=t.toLocalDate() to t.hour
        dayHours[key]=(dayHours[key]?:0.0)+i.hours
        if(i.draw) losses[key]=(losses[key]?:0)+(i.a.showers!!-i.b.showers!!)
    }
    val complete=dayHours.filterValues{it>=0.5}
    val coveredDays=complete.keys.groupBy{it.first}.count{it.value.size>=18}
    val dates=rows.map{Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate()}.distinct()
    val span=if(dates.isEmpty())0L else java.time.temporal.ChronoUnit.DAYS.between(dates.minOrNull()!!,dates.maxOrNull()!!)+1
    val coverage=if(span>0)(complete.size/(span*24.0)).coerceIn(0.0,1.0) else 0.0
    if(coveredDays<14||coverage<0.6)return Forecast(coveredDays,coverage,emptyList())
    val windows=(0..23).mapNotNull{hour->
        val days=complete.keys.filter{it.second==hour && (it.first.dayOfWeek.value>=6)==weekend}
        val events=days.mapNotNull{losses[it]}.filter{it>0}
        if(days.size<4||events.size<3||events.size*2<days.size)null
        else DemandWindow(hour,days.size,events.size, (events.sorted()[events.size/2]).coerceIn(1,5))
    }.sortedWith(compareByDescending<DemandWindow>{it.demandDays.toDouble()/it.days}.thenBy{it.hour})
    return Forecast(coveredDays,coverage,windows.take(4))
}
