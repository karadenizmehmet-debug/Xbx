package com.karadeniz.altyazi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SozlukTest {

    @Test fun anahtar_buyukKucukVeNoktalamaFarkiYok() {
        assertEquals("health", SozlukMantik.anahtar("HEALTH"))
        assertEquals("stones #", SozlukMantik.anahtar("Stones: 12"))
        assertEquals(SozlukMantik.anahtar("Stones: 7"), SozlukMantik.anahtar("STONES - 120"))
    }

    @Test fun sablon_sayilarHashOlur() {
        assertEquals("Taşlar: #", SozlukMantik.sablonYap("Stones: 12", "Taşlar: 12"))
        assertEquals("Sağlık", SozlukMantik.sablonYap("Health", "Sağlık"))
    }

    @Test fun sablon_sayiAdediTutmazsaKaydedilmez() {
        assertNull(SozlukMantik.sablonYap("Stones: 12", "Taşlar"))
        assertNull(SozlukMantik.sablonYap("Stones: 12", "12 taş 3 adet"))
    }

    @Test fun uygula_sayilarYerineKonur() {
        assertEquals("Taşlar: 7", SozlukMantik.uygula("Taşlar: #", "Stones: 7"))
        assertEquals("Sağlık", SozlukMantik.uygula("Sağlık", "Health"))
        assertEquals("3/10 taş", SozlukMantik.uygula("#/# taş", "Stones 3/10"))
    }

    @Test fun uygula_sayiYetmezseNull() {
        assertNull(SozlukMantik.uygula("#/# taş", "Stones"))
    }

    @Test fun otomatikUygun_kisaYazilarGirer_uzunDiyalogGirmez() {
        assertTrue(SozlukMantik.otomatikUygunMu("Health"))
        assertTrue(SozlukMantik.otomatikUygunMu("Stones: 12"))
        assertFalse(SozlukMantik.otomatikUygunMu("Hey, did you hear what happened at the dock last night?"))
        assertFalse(SozlukMantik.otomatikUygunMu("12"))
    }

    @Test fun gosterim_sayilarHash() {
        assertEquals("Stones: #", SozlukMantik.gosterimMetni("Stones: 12"))
    }
}
