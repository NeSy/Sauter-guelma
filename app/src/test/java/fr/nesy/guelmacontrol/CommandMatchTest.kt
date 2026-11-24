package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class CommandMatchTest {
    private val d = Device(-1,-1,true,emptyList(),"Overkiz",HEATER_TYPE,
        listOf("core:TargetDHWTemperatureState" to "69.0", "core:ExpectedNumberOfShowerState" to "4", "modbuslink:DHWBoostModeState" to "off"))
    @Test fun checkReadback() {
        assertTrue(commandMatches(d,"setTargetDHWTemperature",69))
        assertTrue(commandMatches(d,"setExpectedNumberOfShower",4))
        assertFalse(commandMatches(d,"setBoostMode","on"))
        assertFalse(commandMatches(d,"setAntiLegionellosis",0))
    }
}
