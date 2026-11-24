package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HobenPredictionTest{
    @Test fun insufficientHistoryLocksPrediction(){
        val now=1700000000000L
        val rows=listOf(HobenMeasure(now-1800000,20.0,22.0,50,4,false),HobenMeasure(now,20.5,22.0,50,4,false))
        val m=hobenThermalModel(rows,now,java.time.ZoneOffset.UTC)
        assertFalse(m.usable);assertNull(hobenWarmupMinutes(m,20.0,22.0))
    }
    @Test fun limitsExtrapolation(){
        val m=HobenThermalModel(450.0,20,30,10,1.0,0.1,0.3,true)
        assertEquals(90,hobenWarmupMinutes(m,20.5,22.0))
        assertNull(hobenWarmupMinutes(m,18.0,22.0))
    }
    @Test fun csvHasNoCredentials(){
        val csv=hobenCsv(listOf(HobenMeasure(0,20.0,22.0,30,4,true)))
        assertTrue(csv.contains("1970-01-01T00:00:00Z,20.0,22.0,30,4,1"))
    }
}
