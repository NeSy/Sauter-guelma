package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HobenPairingTest{
    @Test fun identifierNormalization(){
        assertEquals("0123456789abcdef0123456789abcdef",normalizeHobenId("01234567-89ab-cdef-0123-456789abcdef"))
        assertNull(normalizeHobenId("wrong"))
    }
    @Test fun frameStructure(){
        val id="0123456789abcdef0123456789abcdef"
        val client="abcdef0123456789abcdef0123456789"
        val frame=hobenOpenFrame(id,client,"Android")
        assertEquals(3,frame[0].toInt())
        assertEquals(id,String(frame,1,32,Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(1,0,34,0),frame.copyOfRange(33,37))
        assertEquals(client,String(frame,37,32,Charsets.UTF_8))
        assertEquals("Android",String(frame,69,frame.size-69,Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(48,0x34,0x12),hobenPinFrame(0x1234))
    }
}
