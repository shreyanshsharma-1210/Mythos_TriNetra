package com.trustmesh.app.vcd.voip

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Signalling over a plain TCP socket on the local network.
 *
 * Deliberately the least infrastructure that can carry an SDP exchange. A proper deployment needs a
 * signalling server with authentication, because two strangers cannot reach each other over the
 * public internet without one — but standing that up would put a backend, an account system and a
 * deployment between us and the question this phase is actually asking, which is whether remote
 * WebRTC audio can be read inside the app at all. Two phones on the same Wi-Fi can answer that
 * today.
 *
 * What this is not: it is not encrypted, not authenticated, and trusts whatever connects to it.
 * Media itself is still DTLS-SRTP encrypted by WebRTC regardless, but the SDP exchange here is in
 * the clear and anyone on the same network can connect. Fine for a POC on a known network, not fine
 * for anything a user would rely on. It is confined to this class so replacing it means replacing
 * one file.
 */
class SignalingClient(private val scope: CoroutineScope) : Closeable {

    /** One signalling message. [payload] is SDP text or a serialised ICE candidate. */
    data class Message(val type: String, val payload: JSONObject)

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readJob: Job? = null

    @Volatile private var closed = false

    var onMessage: ((Message) -> Unit)? = null
    var onConnected: ((String) -> Unit)? = null
    var onClosed: ((String?) -> Unit)? = null

    /** Dials a device that is listening. [address] is "ip" or "ip:port". */
    suspend fun connect(address: String, defaultPort: Int = DEFAULT_PORT): Boolean =
        withContext(Dispatchers.IO) {
            val host = address.substringBefore(':').trim()
            val port = address.substringAfter(':', "").toIntOrNull() ?: defaultPort
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                attach(s)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "could not reach $host:$port", t)
                onClosed?.invoke(
                    "Could not reach $host:$port. Check both devices are on the same Wi-Fi and " +
                        "that the other device is waiting for a call."
                )
                false
            }
        }

    /** Takes over a socket accepted by [SignalingServer]. */
    fun adopt(s: Socket) = attach(s)

    private fun attach(s: Socket) {
        s.tcpNoDelay = true
        socket = s
        writer = s.getOutputStream().bufferedWriter()
        val reader = s.getInputStream().bufferedReader()
        onConnected?.invoke(s.inetAddress?.hostAddress ?: "peer")

        readJob = scope.launch(Dispatchers.IO) { readLoop(reader) }
    }

    private fun readLoop(reader: BufferedReader) {
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val json = try {
                    JSONObject(line)
                } catch (t: Throwable) {
                    Log.w(TAG, "ignoring unparseable signalling line", t)
                    continue
                }
                val type = json.optString("type")
                if (type.isBlank()) continue
                onMessage?.invoke(Message(type, json))
            }
            if (!closed) onClosed?.invoke("The other device closed the connection.")
        } catch (t: Throwable) {
            if (!closed) {
                Log.e(TAG, "signalling read failed", t)
                onClosed?.invoke("Signalling connection lost: ${t.message}")
            }
        }
    }

    fun send(type: String, build: JSONObject.() -> Unit) {
        val json = JSONObject().apply {
            put("type", type)
            build()
        }
        scope.launch(Dispatchers.IO) {
            try {
                val w = writer ?: return@launch
                synchronized(this@SignalingClient) {
                    w.write(json.toString())
                    w.write("\n")
                    w.flush()
                }
            } catch (t: Throwable) {
                if (!closed) Log.e(TAG, "signalling send failed", t)
            }
        }
    }

    override fun close() {
        closed = true
        readJob?.cancel()
        readJob = null
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
    }

    companion object {
        private const val TAG = "SignalingClient"
        const val DEFAULT_PORT = 47_821
        private const val CONNECT_TIMEOUT_MS = 8_000

        /**
         * This device's LAN address, for the user to read out to the other device. Loopback and
         * IPv6 are skipped: the other phone needs something it can actually dial, and typing an
         * IPv6 address into a phone is nobody's idea of a demo.
         */
        fun localIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { addr ->
                    !addr.isLoopbackAddress && addr is java.net.Inet4Address &&
                        addr.isSiteLocalAddress
                }
                ?.hostAddress
        }.getOrNull()

        fun InetAddress.describe(): String = hostAddress ?: toString()
    }
}
