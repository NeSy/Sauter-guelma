package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HobenHistoryTest{
    @Test fun gapsAndTransitions(){
        fun row(min:Long,p:Int)=HobenMeasure(min*60000,20.0,21.0,p,0,false)
        val s=hobenStats(listOf(row(0,0),row(30,40),row(60,40),row(180,0)))
        assertEquals(60.0,s.coveredMinutes,0.01)
        assertEquals(30.0,s.poweredMinutes,0.01)
        assertEquals(1,s.starts)
        assertNull(s.cooling)
    }
}