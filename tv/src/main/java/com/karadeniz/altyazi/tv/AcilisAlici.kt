package com.karadeniz.altyazi.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** TV açılınca servisi kendiliğinden başlatır. */
class AcilisAlici : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try { AltyaziServisi.baslat(context) } catch (_: Exception) {}
        }
    }
}
