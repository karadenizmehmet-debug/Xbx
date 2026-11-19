package com.karadeniz.altyazi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** Ağdaki Altyazı TV uygulamalarını (NSD, "_altyazi._tcp") bulur. Sonuçlar ana iş parçacığında gelir. */
class TvBulucu(
    ctx: Context,
    private val bulundu: (TvCihaz) -> Unit,
    private val kayboldu: (ad: String) -> Unit
) {
    data class TvCihaz(val ad: String, val ip: String, val port: Int) {
        /** TvGonderici'nin beklediği adres: varsayılan portta yalnızca IP. */
        val adres: String get() = if (port == 8765) ip else "$ip:$port"
    }

    companion object { const val TUR = "_altyazi._tcp." }

    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val ana = Handler(Looper.getMainLooper())
    private var dinleyici: NsdManager.DiscoveryListener? = null
    private val kuyruk = ArrayDeque<NsdServiceInfo>()
    private var cozuluyor = false

    fun baslat() {
        durdur()
        val d = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("_altyazi")) ekle(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                ana.post { kayboldu(serviceInfo.serviceName) }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        dinleyici = d
        try { nsd.discoverServices(TUR, NsdManager.PROTOCOL_DNS_SD, d) } catch (_: Exception) { dinleyici = null }
    }

    fun durdur() {
        dinleyici?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        dinleyici = null
        synchronized(this) { kuyruk.clear(); cozuluyor = false }
    }

    @Synchronized private fun ekle(s: NsdServiceInfo) { kuyruk.addLast(s); sonraki() }

    /** NSD aynı anda tek çözümleme yapar: sırayla çöz. */
    @Synchronized private fun sonraki() {
        if (cozuluyor) return
        val s = kuyruk.removeFirstOrNull() ?: return
        cozuluyor = true
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = bitti()
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val ip = serviceInfo.host?.hostAddress
                    if (ip != null && !ip.contains(':')) {
                        val c = TvCihaz(serviceInfo.serviceName, ip, serviceInfo.port)
                        ana.post { bulundu(c) }
                    }
                    bitti()
                }
            })
        } catch (_: Exception) { cozuluyor = false }
    }

    @Synchronized private fun bitti() { cozuluyor = false; sonraki() }
}
