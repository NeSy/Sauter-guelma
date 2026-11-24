package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HobenConfigTest{
    @Test fun singleRegisterCommands(){
        assertArrayEquals(byteArrayOf(13,-1,-16,0,0,0,6,1,6,5,0,0,1),hobenOverrideFrame(HobenOverride.Power(true)))
        assertArrayEquals(byteArrayOf(13,-1,-16,0,0,0,6,1,6,5,4,0,2),hobenOverrideFrame(HobenOverride.Vent(2)))
        assertArrayEquals(byteArrayOf(13,-1,-16,0,0,0,6,1,6,7,0,0,2),hobenOverrideFrame(HobenOverride.Mode(2)))
    }
    @Test fun delayedOverride(){assertEquals(60,hobenOverrideFrame(HobenOverride.Start(44,120,60))[17].toInt())}
    @Test fun programPreservesOtherSlots(){
        val original=List(21){0}
        val slot=HobenSlot(true,360,480,true,220,70)
        val changed=changeSlot(original,1,slot)
        assertEquals(slot,decodeSlot(changed,1))
        assertEquals(original.take(3),changed.take(3))
        assertEquals(original.drop(6),changed.drop(6))
    }
}
