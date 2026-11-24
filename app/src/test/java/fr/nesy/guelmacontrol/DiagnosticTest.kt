package fr.nesy.guelmacontrol

import org.junit.Assert.*
import org.junit.Test

class DiagnosticTest {
    @Test fun valuesAreFiltered() {
        assertEquals("42.5", diagnosticValue("42.5"))
        assertEquals("true", diagnosticValue(true))
        assertEquals("masqué (texte ou structure)", diagnosticValue("user@example.com"))
        assertEquals("non renseigné", diagnosticValue(null))
    }
    @Test fun demoIsMarked() {
        assertTrue(diagnosticReport(emptyList(), true).contains("DÉMO"))
    }
}
