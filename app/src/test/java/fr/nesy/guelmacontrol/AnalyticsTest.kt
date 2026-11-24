package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class AnalyticsTest {
    private fun sample(t:Long,showers:Int,temp:Double,energy:Long=0)=Sample(t,showers,temp,temp,69.0,0.0,energy,"manualEcoInactive","off","off","off")
    @Test fun sparseDataCannotPredict(){
        assertTrue(demandForecast(listOf(sample(0,5,65.0),sample(1800000,4,60.0)),false).windows.isEmpty())
    }
    @Test fun counterResetAndGapExcluded(){
        assertTrue(energyIntervals(listOf(sample(0,5,65.0,50),sample(1800000,5,64.0,10))).isEmpty())
        assertTrue(energyIntervals(listOf(sample(0,5,65.0,50),sample(7200000,5,64.0,100))).isEmpty())
    }
    @Test fun medianAndTemperatureDrop(){
        assertEquals(2.5,median(listOf(1.0,4.0))!!,0.001)
        assertTrue(intervals(listOf(sample(0,5,65.0),sample(1800000,4,60.0))).single().draw)
    }
}
