package com.trustmesh.app.vcd.voip

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Listens for incoming calls for as long as the device is available.
 *
 * Separate from [SignalingClient] because the two have different lifetimes. A client belongs to one
 * call and dies with it; this outlives calls entirely — a phone that is reachable has to keep a
 * socket open the whole time it is reachable, in the same way a phone that can be rung is a phone
 * that is switched on. Folding the accept loop into the per-call object, as the first version did,
 * meant a device could only be called during the few seconds it sat on a "waiting" screen.
 */
class SignalingServer(private val scope: CoroutineScope) : Closeable {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile private var closed = false

    /** Called on an IO thread for every inbound connection. */
    var onConnection: ((Socket) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val listening: Boolean get() = serverSocket != null && !closed

    fun start(port: Int = SignalingClient.DEFAULT_PORT): Boolean {
        if (serverSocket != null) return true
        closed = false

        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "could not listen on port $port", t)
            onError?.invoke("Could not listen for calls on port $port: ${t.message}")
            return false
        }
        serverSocket = server

        acceptJob = scope.launch(Dispatchers.IO) {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (t: Throwable) {
                    if (!closed) {
                        Log.e(TAG, "accept failed", t)
                        onError?.invoke("Stopped listening for calls: ${t.message}")
                    }
                    break
                }
                if (closed) {
                    runCatching { socket.close() }
                    break
                }
                Log.i(TAG, "accepted inbound connection from ${socket.inetAddress?.hostAddress}")
                val handler = onConnection
                if (handler == null) {
                    // Nothing is prepared to take the call, so refuse it rather than leaving the
                    // caller ringing into a socket nobody will ever answer.
                    Log.w(TAG, "no onConnection handler set — refusing inbound call")
                    runCatching { socket.close() }
                } else {
                    handler(socket)
                }
            }
        }
        Log.i(TAG, "listening for calls on port $port")
        return true
    }

    override fun close() {
        closed = true
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private companion object {
        const val TAG = "SignalingServer"
    }
}
