package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HobenReadTest{
    @Test fun fixedReadOnlyFrame(){
        assertArrayEquals(byteArrayOf(13,-1,-1,0,0,0,6,1,4,4,0,0,20),hobenV4ReadFrame())
    }
    @Test fun signedTemperaturesAndFlags(){
        val r=IntArray(20)
        r[0]=513;r[1]=220;r[2]=15;r[3]=120;r[4]=2;r[6]=(45 shl 8)+5;r[7]=204;r[8]=220;r[11]=32
        val x=decodeHobenV4(r,123)
        assertEquals(20.4,x.ambient!!,0.001);assertEquals(22.0,x.target!!,0.001)
        assertEquals(45,x.power);assertEquals(2,x.mode);assertEquals(5,x.state);assertEquals(2,x.vent)
        assertTrue(x.activeOverride);assertFalse(x.scheduledOverride);assertEquals(120,x.overrideMinutes)
        r[7]=65526;assertEquals(-1.0,decodeHobenV4(r,0).ambient!!,0.001)
    }
    @Test fun rejectsNonReadResponse(){
        try{decodeHobenReadPdu(byteArrayOf(6,0),0);fail("Expected rejection")}catch(_:HobenReadException){}
    }
}
