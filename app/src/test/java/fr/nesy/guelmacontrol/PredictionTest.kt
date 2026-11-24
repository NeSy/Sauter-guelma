package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId
class PredictionTest {
    private fun sample(t:Long,s:Int,temp:Double,p:Double=0.0)=Sample(t,s,temp,temp,69.0,p,0,"manualEcoInactive","off","off",if(p>0)"on"else "off")
    @Test fun noRecommendationsWithoutHistory(){
        val now=ZonedDateTime.parse("2026-11-24T08:00:00+01:00")
        val plan=createPlan(emptyList(),now,5,1)
        assertTrue(plan.windows.isEmpty())
        assertFalse(plan.profile.validation.passed)
    }
    @Test fun percentileAndHourSplit(){
        assertEquals(5,quantile90(listOf(0,0,1,2,5)))
        val rows=listOf(sample(2700000,4,65.0),sample(4500000,3,60.0))
        assertTrue(hourlyEvidence(rows,ZoneId.of("UTC")).isEmpty())
    }
    @Test fun validationMustBeatBaseline(){
        assertFalse(Validation(80,10,0.30,0.20).passed)
        assertTrue(Validation(80,10,0.10,0.20).passed)
    }
}
