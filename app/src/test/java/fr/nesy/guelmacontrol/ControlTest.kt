package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class ControlTest {
    private val device = Device(-1,-1,true,emptyList(),"Overkiz",HEATER_TYPE,
        listOf("modbuslink:DHWAbsenceModeState" to "off", "modbuslink:DHWBoostModeState" to "off",
            "modbuslink:DHWModeState" to "manualEcoInactive", "core:MinimalTemperatureManualModeState" to "50", "core:MaximalTemperatureManualModeState" to "69",
            "core:MinimalShowerManualModeState" to "1", "core:MaximalShowerManualModeState" to "5"),
        listOf("setDHWMode","setBoostMode","setTargetDHWTemperature","setExpectedNumberOfShower","setAntiLegionellosis"))
    @Test fun allowlistedCommands() {
        assertTrue(validCommand(device,"setDHWMode","autoMode"))
        assertTrue(validCommand(device,"setBoostMode","on"))
        assertTrue(validCommand(device,"setExpectedNumberOfShower",4))
        assertTrue(validCommand(device,"setTargetDHWTemperature",69))
        assertFalse(validCommand(device,"setTargetDHWTemperature",70))
        assertFalse(validCommand(device,"setExpectedNumberOfShower",0))
        assertFalse(validCommand(device,"setAntiLegionellosis",0))
        assertFalse(validCommand(device.copy(available=false),"setBoostMode","on"))
    }
}
