package com.karadeniz.altyazi

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOranTest {

    private fun ayni(beklenen: Oran, gercek: Oran) {
        assertEquals(beklenen.sol, gercek.sol, 1e-4f)
        assertEquals(beklenen.ust, gercek.ust, 1e-4f)
        assertEquals(beklenen.sag, gercek.sag, 1e-4f)
        assertEquals(beklenen.alt, gercek.alt, 1e-4f)
    }

    @Test fun ekrandanVideoya_oranVerir() {
        val video = Oran(0f, 100f, 1000f, 662.5f)
        ayni(Oran(0.1f, 0f, 0.9f, 1f), MetinIsleme.ekrandanVideoya(Oran(100f, 100f, 900f, 662.5f), video))
    }

    @Test fun ekrandanVideoya_tasaniKirpar() {
        val video = Oran(0f, 100f, 1000f, 662.5f)
        ayni(Oran(0f, 0f, 1f, 1f), MetinIsleme.ekrandanVideoya(Oran(-50f, 0f, 1200f, 900f), video))
    }

    @Test fun ekrandanVideoya_bosVideoTumKareyiVerir() {
        ayni(Oran(0f, 0f, 1f, 1f), MetinIsleme.ekrandanVideoya(Oran(1f, 1f, 2f, 2f), Oran(0f, 0f, 0f, 0f)))
    }

    @Test fun videodanEkrana_geriCevirir() {
        val video = Oran(0f, 100f, 1000f, 662.5f)
        val bolge = Oran(0.1f, 0.7f, 0.9f, 0.95f)
        ayni(bolge, MetinIsleme.ekrandanVideoya(MetinIsleme.videodanEkrana(bolge, video), video))
    }
}
