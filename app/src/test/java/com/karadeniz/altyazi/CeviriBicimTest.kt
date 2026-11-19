package com.karadeniz.altyazi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CeviriBicimTest {
    @Test fun buyukHarfCumleDuzeninegecer() {
        assertEquals("Move arm", CeviriBicim.duzelt("MOVE ARM"))
        assertEquals("No prob", CeviriBicim.duzelt("No prob"))
        assertEquals("RB", CeviriBicim.duzelt("RB"))
    }

    @Test fun ceviriOrijinalGibiBuyukHarfOlur() {
        assertEquals("KOLU HAREKET ETTİR", CeviriBicim.geriBuyut("MOVE ARM", "Kolu hareket ettir"))
        assertEquals("Sorun değil", CeviriBicim.geriBuyut("No prob", "Sorun değil"))
    }

    @Test fun topluCeviriSatirlaraBolunur() {
        assertEquals(listOf("Bir", "İki"), CeviriBicim.satirlaraBol("Bir\nİki\n", 2).let { it ?: CeviriBicim.satirlaraBol("Bir\nİki", 2) })
        assertEquals(listOf("Bir", "İki"), CeviriBicim.satirlaraBol("Bir \n İki", 2))
        assertNull(CeviriBicim.satirlaraBol("Bir İki", 2))
    }
}
