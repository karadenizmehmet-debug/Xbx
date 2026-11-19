package com.karadeniz.altyazi.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings

/** TV'yi ağda "_altyazi._tcp" olarak duyurur; telefon IP yazmadan TV'yi adıyla bulur. */
class Duyurucu(private val ctx: Context) {
    companion object { const val TUR = "_altyazi._tcp." }

    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var dinleyici: NsdManager.RegistrationListener? = null

    /** TV'nin ayarlardaki adı (ör. "Salon TV"), yoksa model adı. */
    private fun ad(): String =
        Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    fun baslat(port: Int) {
        durdur()
        val bilgi = NsdServiceInfo().apply {
            serviceName = ad()
            serviceType = TUR
            this.port = port
        }
        val d = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        dinleyici = d
        try { nsd.registerService(bilgi, NsdManager.PROTOCOL_DNS_SD, d) } catch (_: Exception) { dinleyici = null }
    }

    fun durdur() {
        dinleyici?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        dinleyici = null
    }
}
