package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HobenOverrideTest{
    @Test fun start22ForTwoHours(){
        assertArrayEquals(byteArrayOf(13,-1,-16,0,0,0,13,1,16,5,1,0,3,6,0,-36,0,0,0,120),hobenOverrideFrame(HobenOverride.Start(44,120)))
    }
    @Test fun cancelOnlyTiming(){
        assertArrayEquals(byteArrayOf(13,-1,-16,0,0,0,11,1,16,5,2,0,2,4,0,0,0,0),hobenOverrideFrame(HobenOverride.Cancel))
    }
    @Test(expected=IllegalArgumentException::class) fun refusesOutsideRange(){hobenOverrideFrame(HobenOverride.Start(100,120))}
    private fun sample():HobenSnapshot=decodeHobenV4(IntArray(20).apply{this[0]=1;this[7]=207;this[8]=215},1)
    @Test fun preflight(){
        assertNull(hobenOverrideBlock(sample(),HobenOverride.Start(44,120)))
        assertNotNull(hobenOverrideBlock(sample().copy(onOff=0),HobenOverride.Start(44,120)))
        assertNotNull(hobenOverrideBlock(sample().copy(faults=1),HobenOverride.Start(44,120)))
        assertNotNull(hobenOverrideBlock(sample().copy(info=128),HobenOverride.Start(44,120)))
        assertNotNull(hobenOverrideBlock(sample().copy(activeOverride=true),HobenOverride.Start(44,120)))
    }
    @Test fun actualConfirmation(){
        val s=sample().copy(activeOverride=true,overrideTemp=22.0,overrideMinutes=119)
        assertTrue(hobenOverrideMatches(s,HobenOverride.Start(44,120)))
        assertFalse(hobenOverrideMatches(s.copy(overrideTemp=21.5),HobenOverride.Start(44,120)))
        assertTrue(hobenOverrideMatches(sample(),HobenOverride.Cancel))
    }
}
