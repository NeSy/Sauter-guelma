package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HobenProfilesTest{
    @Test fun restorePreservesLimitsAndDisablesOnly(){
        val current=changeSlot(List(21){0},0,HobenSlot(true,360,480,false,210,65))
        val saved=changeSlot(List(21){0},0,HobenSlot(true,420,510,true,220,90))
        val restored=restoreHobenDay(current,saved)
        assertEquals(HobenSlot(true,420,510,true,220,65),decodeSlot(restored,0))
        val disabled=restoreHobenDay(current,List(21){0})
        assertEquals(current[0] and 32767,disabled[0])
        assertEquals(current.drop(1),disabled.drop(1))
    }
}