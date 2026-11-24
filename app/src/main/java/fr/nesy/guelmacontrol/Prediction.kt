package fr.nesy.guelmacontrol

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class HourEvidence(val hours: Double = 0.0, val loss: Int = 0)
internal data class ProfilePoint(val hour:Int,val weekend:Boolean,val observations:Int,val probability:Double,val reserve:Int)
internal data class Validation(val hours:Int,val eventHours:Int,val brier:Double?,val baseline:Double?) {
    val passed:Boolean get() = hours>=48 && eventHours>=5 && brier!=null && baseline!=null && brier<baseline
}
internal data class LearnedProfile(val coverage:Double,val completeDays:Int,val points:List<ProfilePoint>,val validation:Validation)
internal data class Recovery(val minutesPerShower:Double?,val intervals:Int)
internal data class Advice(val at:ZonedDateTime,val probability:Double,val reserve:Int,val count:Int,val leadMinutes:Int?)
internal data class DailyPlan(val profile:LearnedProfile,val recovery:Recovery,val windows:List<Advice>,val reason:String)

internal fun hourlyEvidence(rows:List<Sample>,zone:ZoneId):Map<Pair<LocalDate,Int>,HourEvidence> {
    val buckets=mutableMapOf<Pair<LocalDate,Int>,HourEvidence>()
    for(i in intervals(rows)) {
        // Split coverage at real local-hour boundaries, including DST.
        var cursor=Instant.ofEpochMilli(i.a.time).atZone(zone)
        val end=Instant.ofEpochMilli(i.b.time)
        val midpoint=Instant.ofEpochMilli(i.a.time+(i.b.time-i.a.time)/2).atZone(zone)
        val eventKey=midpoint.toLocalDate() to midpoint.hour
        while(cursor.toInstant()<end) {
            val boundary=cursor.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant()
            val stop=if(boundary<end)boundary else end
            val length=(stop.toEpochMilli()-cursor.toInstant().toEpochMilli())/3600000.0
            if(length<=0)break
            val key=cursor.toLocalDate() to cursor.hour
            val old=buckets[key]?:HourEvidence()
            buckets[key]=old.copy(hours=(old.hours+length).coerceAtMost(1.0))
            cursor=stop.atZone(zone)
        }
        if(i.draw) {
            val old=buckets[eventKey]?:HourEvidence()
            buckets[eventKey]=old.copy(loss=old.loss+(i.a.showers!!-i.b.showers!!))
        }
    }
    return buckets.filterValues{it.hours>=0.5}
}

internal fun quantile90(values:List<Int>):Int {
    if(values.isEmpty())return 0
    val sorted=values.sorted()
    return sorted[(ceil(sorted.size*0.9).toInt()-1).coerceIn(0,sorted.lastIndex)]
}
private fun fitProfile(evidence:Map<Pair<LocalDate,Int>,HourEvidence>):List<ProfilePoint> =
    evidence.entries.groupBy { (key,_) -> (key.first.dayOfWeek.value>=6) to key.second }
        .mapNotNull { (key,entries) ->
            if(entries.size<4)return@mapNotNull null
            val losses=entries.map{it.value.loss}
            val n=losses.count{it>0}
            ProfilePoint(key.second,key.first,entries.size,(n+1.0)/(entries.size+2.0),quantile90(losses))
        }

internal fun learnProfile(rows:List<Sample>,now:ZonedDateTime):LearnedProfile {
    val today=now.toLocalDate()
    val first=today.minusDays(30)
    val evidence=hourlyEvidence(rows,now.zone).filterKeys{it.first>=first && it.first<today}
    val covered=evidence.keys.groupBy{it.first}.count{it.value.size>=18}
    val coverage=evidence.size/(30.0*24)
    val split=today.minusDays(7)
    val train=evidence.filterKeys{it.first<split}
    val test=evidence.filterKeys{it.first>=split}
    val trained=fitProfile(train).associateBy{it.weekend to it.hour}
    val validationPairs=test.entries.mapNotNull{ (key,value) ->
        val p=trained[(key.first.dayOfWeek.value>=6) to key.second]?:return@mapNotNull null
        p.probability to if(value.loss>0)1.0 else 0.0
    }
    val baselineRate=if(train.isEmpty())0.0 else train.values.count{it.loss>0}.toDouble()/train.size
    val validation=Validation(validationPairs.size,validationPairs.count{it.second==1.0},
        validationPairs.takeIf{it.isNotEmpty()}?.map{(p,y)->(p-y)*(p-y)}?.average(),
        validationPairs.takeIf{it.isNotEmpty()}?.map{(_,y)->(baselineRate-y)*(baselineRate-y)}?.average())
    return LearnedProfile(coverage,covered,fitProfile(evidence),validation)
}

internal fun recoveryModel(rows:List<Sample>):Recovery {
    val rates=intervals(rows).mapNotNull{i->
        val a=i.a;val b=i.b
        val gain=b.showers!!-a.showers!!
        if(i.draw||gain<=0||a.power==null||b.power==null||a.power<=0||b.power<=0)return@mapNotNull null
        if(a.t1==null||b.t1==null||a.t2==null||b.t2==null)return@mapNotNull null
        if(b.t1+b.t2<=a.t1+a.t2)return@mapNotNull null
        (i.hours*60/gain).takeIf{it in 2.0..120.0}
    }.sorted()
    return Recovery(if(rates.size>=8)rates[(ceil(rates.size*.75).toInt()-1).coerceIn(0,rates.lastIndex)]else null,rates.size)
}

internal fun createPlan(rows:List<Sample>,now:ZonedDateTime,capacity:Int,margin:Int):DailyPlan {
    val profile=learnProfile(rows,now)
    val recovery=recoveryModel(rows.filter{it.time>=now.minusDays(30).toInstant().toEpochMilli()})
    val reason=when{
        profile.completeDays<14 -> "Au moins 14 journées bien couvertes sont nécessaires."
        profile.coverage<0.60 -> "Couverture horaire insuffisante sur 30 jours (objectif 60 %)."
        !profile.validation.passed -> "La prévision n’a pas encore démontré un avantage sur la semaine de validation."
        capacity !in 1..20 -> "Capacité de réserve non confirmée."
        else -> ""
    }
    if(reason.isNotEmpty())return DailyPlan(profile,recovery,emptyList(),reason)
    val profileMap=profile.points.associateBy{it.weekend to it.hour}
    val start=now.withMinute(0).withSecond(0).withNano(0)
    val windows=(0..23).mapNotNull{offset->
        val at=start.plusHours(offset.toLong())
        val p=profileMap[(at.dayOfWeek.value>=6) to at.hour]?:return@mapNotNull null
        if(p.probability<.5||p.reserve<=0||p.observations<4)return@mapNotNull null
        val reserve=(p.reserve+margin.coerceIn(0,3)).coerceIn(1,capacity)
        Advice(at,p.probability,reserve,p.observations,recovery.minutesPerShower?.let{ceil(it*reserve+30).toInt()})
    }
    return DailyPlan(profile,recovery,windows,if(windows.isEmpty())"Aucun pic suffisamment récurrent sur les prochaines 24 h."else "")
}

internal fun planText(plan:DailyPlan):String=buildString{
    appendLine("Guelma Control — recommandations (aucune commande)")
    appendLine("Couverture : "+(plan.profile.coverage*100).roundToInt()+" % ; jours bien couverts : "+plan.profile.completeDays)
    if(plan.reason.isNotBlank())appendLine(plan.reason)
    plan.windows.forEach{w->appendLine(w.at.toLocalDateTime().toString()+" | réserve "+w.reserve+" | probabilité indicative "+(w.probability*100).roundToInt()+" %")}
}
