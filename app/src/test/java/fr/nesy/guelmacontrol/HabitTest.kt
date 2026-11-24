package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class HabitTest {
    private fun sample(t:Long,s:Int,temp:Double,target:Double=69.0) = Sample(t,s,temp,temp,target,0.0,0,"manualEcoInactive","off","off","off")
    @Test fun detectsLikelyDrawWithoutCountingActualShowers() {
        val a=sample(0,4,65.0)
        val b=sample(1800000,3,60.0)
        assertEquals(1,analyzeHabits(listOf(a,b),ZoneId.of("UTC")).likelyEvents)
    }
    @Test fun ignoresGapsAndChangedTarget() {
        val a=sample(0,4,65.0)
        assertEquals(0,analyzeHabits(listOf(a,sample(7200000,3,60.0)),ZoneId.of("UTC")).likelyEvents)
        assertEquals(0,analyzeHabits(listOf(a,sample(1800000,3,60.0,60.0)),ZoneId.of("UTC")).likelyEvents)
    }
}
