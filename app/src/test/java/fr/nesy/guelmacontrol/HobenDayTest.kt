package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HobenDayTest{
    @Test fun nativeWeekIndex(){
        assertEquals(1,hobenDayIndex(java.time.DayOfWeek.MONDAY))
        assertEquals(6,hobenDayIndex(java.time.DayOfWeek.SATURDAY))
        assertEquals(0,hobenDayIndex(java.time.DayOfWeek.SUNDAY))
    }
    @Test fun nativeDates(){
        val counters=MutableList(35){0}
        counters[5]=15;counters[6]=(23 shl 8) or 11
        counters[7]=5;counters[8]=(26 shl 8) or 10
        val d=HobenDetails(1,List(7){0},List(21){0},counters)
        assertEquals(java.time.LocalDate.of(2023,11,15),d.commissioning)
        assertEquals(java.time.LocalDate.of(2026,10,5),d.maintenance)
    }
}