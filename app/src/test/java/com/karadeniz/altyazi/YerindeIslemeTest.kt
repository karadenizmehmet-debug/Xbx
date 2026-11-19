package com.karadeniz.altyazi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YerindeIslemeTest {
    @Test fun menuAdlariVeSayilarElenir() {
        assertFalse(YerindeIsleme.uygunMu("Messages", 3))
        assertFalse(YerindeIsleme.uygunMu("Camera Map", 3))
        assertFalse(YerindeIsleme.uygunMu("$1,332 12:45 pm", 2))
        assertTrue(YerindeIsleme.uygunMu("Meet me at the docks", 3))
        assertTrue(YerindeIsleme.uygunMu("Mission failed", 2))
    }

    @Test fun kirpiktanVideoyaCevirir() {
        val bolge = Oran(0.5f, 0.5f, 1f, 1f)
        val k = YerindeIsleme.kirpiktanVideoya(Oran(0f, 50f, 100f, 100f), 200, 100, bolge)
        assertEquals(0.5f, k.sol, 1e-4f); assertEquals(0.75f, k.ust, 1e-4f)
        assertEquals(0.75f, k.sag, 1e-4f); assertEquals(1f, k.alt, 1e-4f)
    }

    @Test fun kaymaAlgilanir() {
        val a = listOf(Blok("x", Oran(0.1f, 0.1f, 0.2f, 0.2f)))
        assertFalse(YerindeIsleme.kaydiMi(a, listOf(Blok("x", Oran(0.105f, 0.1f, 0.2f, 0.2f)))))
        assertTrue(YerindeIsleme.kaydiMi(a, listOf(Blok("x", Oran(0.1f, 0.15f, 0.2f, 0.25f)))))
    }

    @Test fun kumandaDugmeleriAtilir() {
        assertEquals("CLOSE CLAW", YerindeIsleme.dugmeleriAt("CLOSE CLAW RB"))
        assertEquals("MOVE ARM", YerindeIsleme.dugmeleriAt("MOVE ARM (L)"))
        assertEquals("SET DESTINATION", YerindeIsleme.dugmeleriAt("SET DESTINATION A"))
        assertEquals("I see you", YerindeIsleme.dugmeleriAt("I see you"))
    }

    @Test fun komutSatirlariAyriCevrilir() {
        assertTrue(YerindeIsleme.satirSatirMi(listOf("CLOSE CLAW", "MOVE ARM")))
        assertFalse(YerindeIsleme.satirSatirMi(listOf("Hey, are you coming to the party", "tonight or not?")))
        assertFalse(YerindeIsleme.satirSatirMi(listOf("Mission passed")))
    }
}
