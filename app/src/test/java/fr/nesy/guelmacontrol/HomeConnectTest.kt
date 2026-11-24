package fr.nesy.guelmacontrol
import org.junit.Assert.*
import org.junit.Test
class HomeConnectTest{
    @Test fun kinds(){assertEquals("Four",hcKind("Oven"));assertEquals("Plaques",hcKind("Hob"));assertEquals("Plaques",hcKind("Cooktop"));assertEquals("Hotte",hcKind("Hood"))}
    @Test fun clientValidation(){assertTrue(hcClientValid("a".repeat(64)));assertFalse(hcClientValid("a".repeat(63)));assertFalse(hcClientValid("é".repeat(64)))}
    @Test fun display(){assertEquals("1 h 30 min",hcMinutes("5400"));assertNull(hcMinutes("-1"));assertEquals("En cours",hcEnum("BSH.Common.EnumType.OperationState.Run"))}
}
