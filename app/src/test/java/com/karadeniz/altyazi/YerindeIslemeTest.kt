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

    @Test fun egikKutuAciVeBoyutVerir() {
        val duz = YerindeIsleme.egikKutu(floatArrayOf(0f, 100f, 100f, 0f), floatArrayOf(0f, 0f, 20f, 20f))
        assertEquals(0f, duz.aci, 1e-3f)
        assertEquals(0f, duz.kutu.sol, 1e-3f); assertEquals(100f, duz.kutu.sag, 1e-3f)
        assertEquals(20f, duz.kutu.alt - duz.kutu.ust, 1e-3f)
        // 10° eğik: üst kenar sağa doğru aşağı iniyor
        val r = Math.toRadians(10.0)
        val c = Math.cos(r).toFloat(); val sn = Math.sin(r).toFloat()
        val egik = YerindeIsleme.egikKutu(
            floatArrayOf(0f, 100f * c, 100f * c - 20f * sn, -20f * sn),
            floatArrayOf(0f, 100f * sn, 100f * sn + 20f * c, 20f * c)
        )
        assertEquals(10f, egik.aci, 1e-2f)
        assertEquals(100f, egik.kutu.sag - egik.kutu.sol, 1e-2f)
        assertEquals(20f, egik.kutu.alt - egik.kutu.ust, 1e-2f)
    }

    @Test fun telefonEkraniAlgilanir() {
        val beyaz = 0xFFEDEDF2.toInt(); val mavi = 0xFF1E5AC8.toInt(); val koyu = 0xFF202830.toInt()
        // Telefon: yarısı açık gri ekran
        assertTrue(YerindeIsleme.telefonEkraniMi(IntArray(100) { if (it < 50) beyaz else koyu }))
        // Bölgenin küçük bir kısmını kaplayan telefon (%20) yine algılanır
        assertTrue(YerindeIsleme.telefonEkraniMi(IntArray(100) { if (it < 20) beyaz else koyu }))
        // Harita: mavi ağırlıklı, birkaç beyaz yazı
        assertFalse(YerindeIsleme.telefonEkraniMi(IntArray(100) { if (it < 8) beyaz else mavi }))
        assertFalse(YerindeIsleme.telefonEkraniMi(IntArray(0)))
    }

    @Test fun telefonOrani_dusukEsikAcikGriTelefonuYakalar() {
        val acikGri = 0xFF9A9AA6.toInt(); val koyu = 0xFF202830.toInt()
        val p = IntArray(100) { if (it < 9) acikGri else koyu }
        assertEquals(0.09f, YerindeIsleme.telefonOrani(p), 0.001f)
        assertTrue(YerindeIsleme.telefonEkraniMi(p, 0.08f))
        assertFalse(YerindeIsleme.telefonEkraniMi(p, 0.15f))
    }
}
