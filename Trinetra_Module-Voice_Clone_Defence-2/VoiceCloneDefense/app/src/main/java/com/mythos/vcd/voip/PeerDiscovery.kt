package com.mythos.vcd.voip

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.ArrayDeque

/**
 * Finds other TRINETRA devices on the same Wi-Fi, using mDNS via [NsdManager].
 *
 * This replaces typing an IP address, which was the single least convincing thing about the call
 * flow: nobody dials a phone by entering its network address. Each device advertises itself under a
 * readable name and browses for the others, so the dialler can show a list of people to tap.
 *
 * No server is involved. Devices announce themselves on the local network and hear each other
 * directly, which keeps the "no backend" property of the POC while removing the part that made it
 * look like a debug tool.
 */
class PeerDiscovery(context: Context) : Closeable {

    data class Peer(
        val name: String,
        val host: String,
        val port: Int,
    ) {
        val address: String get() = "$host:$port"
    }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _advertisedAs = MutableStateFlow<String?>(null)
    val advertisedAs: StateFlow<String?> = _advertisedAs.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Resolves are serialised on purpose.
     *
     * NsdManager on several Android versions fails every concurrent resolve with FAILURE_ALREADY_
     * ACTIVE, and a handful of devices appearing at once is exactly the normal case here. Queueing
     * them costs a few hundred milliseconds and avoids a discovery list that is silently missing
     * whichever peers happened to arrive together.
     */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val lock = Any()

    /** Announces this device so others can find it. [displayName] is what they will see. */
    fun advertise(displayName: String, port: Int) {
        if (registrationListener != null) return

        val info = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Android appends "(2)" and so on when the name collides; report what was actually
                // registered rather than what we asked for.
                _advertisedAs.value = info.serviceName
                Log.i(TAG, "advertising as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "advertise failed: $errorCode")
                _advertisedAs.value = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _advertisedAs.value = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _advertisedAs.value = null
            }
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.e(TAG, "could not register service", it)
                registrationListener = null
            }
    }

    fun startDiscovery() {
        if (discoveryListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _searching.value = true
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                // Our own advertisement comes back to us; showing yourself in a list of people to
                // call would be absurd.
                if (info.serviceName == _advertisedAs.value) return
                enqueueResolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.name == info.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _searching.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "discovery start failed: $errorCode")
                _searching.value = false
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _searching.value = false
            }
        }
        discoveryListener = listener
        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.e(TAG, "could not start discovery", it)
            discoveryListener = null
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(lock) {
            resolveQueue.add(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(lock) {
            val item = resolveQueue.poll()
            if (item == null) resolving = false
            item
        } ?: return

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                resolveNext()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null) {
                    val peer = Peer(info.serviceName, host, info.port)
                    _peers.value = (_peers.value.filterNot { it.name == peer.name } + peer)
                        .sortedBy { it.name.lowercase() }
                }
                resolveNext()
            }
        }

        @Suppress("DEPRECATION")
        runCatching { nsd.resolveService(next, listener) }
            .onFailure {
                Log.w(TAG, "resolve threw", it)
                resolveNext()
            }
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        _searching.value = false
        synchronized(lock) {
            resolveQueue.clear()
            resolving = false
        }
    }

    fun stopAdvertising() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
        _advertisedAs.value = null
    }

    override fun close() {
        stopDiscovery()
        stopAdvertising()
        _peers.value = emptyList()
    }

    companion object {
        private const val TAG = "PeerDiscovery"

        /** Must end in a dot; NsdManager is fussy about it on some versions. */
        const val SERVICE_TYPE = "_trinetra._tcp."
    }
}
