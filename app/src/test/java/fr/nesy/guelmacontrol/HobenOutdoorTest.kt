package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HobenOutdoorTest{
    @Test fun requiresSameLocationAndSufficientSamples(){
        val rows=(0..8).map{n->HobenMeasure(n*1800000L,22.0-n*0.1,22.0,0,0,false,5.0,"a")}
        assertEquals(1,hobenOutdoorStats(rows,"a").size)
        assertEquals(0.2,hobenOutdoorStats(rows,"a").single().rate,0.001)
        assertTrue(hobenOutdoorStats(rows,"b").isEmpty())
        assertTrue(hobenOutdoorStats(rows.take(5),"a").isEmpty())
    }
}
