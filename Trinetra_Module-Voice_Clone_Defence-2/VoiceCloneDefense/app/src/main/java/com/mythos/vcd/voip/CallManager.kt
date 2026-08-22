package com.mythos.vcd.voip

import android.content.Context
import android.os.Build
import android.util.Log
import com.mythos.vcd.VcdApp
import com.mythos.vcd.data.db.CallHistoryEntity
import com.mythos.vcd.service.VoipCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.net.Socket

/**
 * Single entry point for VoIP calls, and the one place the UI reads call state from.
 *
 * Holds the two things that outlive any individual call — the mDNS advertisement and the listening
 * socket — because being reachable is a property of the device, not of a call. A phone you can only
 * ring while its owner is staring at a "waiting" screen is not a phone anyone would use.
 *
 * At most one call exists at a time. A second inbound invite while a call is up is refused rather
 * than silently replacing it.
 */
object CallManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var session: CallSession? = null
    private var discovery: PeerDiscovery? = null
    private var server: SignalingServer? = null
    private var ringer: Ringer? = null
    private var app: VcdApp? = null

    /** Contact every inbound caller is checked against, chosen before the phone starts ringing. */
    private var defaultContactId: Long? = null

    /**
     * The address-book name the user tapped to start this call, if they dialled from Contacts.
     *
     * Kept separate from the peer's advertised name because they are different facts: one is who
     * the user meant to reach, the other is which device actually answered. Recents shows the first
     * and the call screen shows the second, and conflating them would let the list claim a call
     * reached someone it did not.
     */
    private var pendingLabel: String? = null

    private val emptyPeers = MutableStateFlow<List<PeerDiscovery.Peer>>(emptyList())
    val peers: StateFlow<List<PeerDiscovery.Peer>>
        get() = discovery?.peers ?: emptyPeers

    private val notSearching = MutableStateFlow(false)
    val searching: StateFlow<Boolean>
        get() = discovery?.searching ?: notSearching

    val inCall: Boolean get() = _state.value.active

    // ------------------------------------------------------------- availability

    /**
     * Makes this device reachable: advertises it over mDNS and starts listening for invites.
     *
     * [contactId] is the enrolled voiceprint inbound callers get checked against. It is chosen here,
     * before the phone rings, because there is no sensible moment to ask during an incoming call.
     */
    fun goAvailable(app: VcdApp, contactId: Long?) {
        this.app = app
        defaultContactId = contactId
        if (_available.value) return

        ringer = ringer ?: Ringer(app)

        val srv = SignalingServer(scope).also { server = it }
        srv.onConnection = ::onInboundConnection
        srv.onError = { message ->
            Log.w(TAG, message)
            _available.value = false
        }
        if (!srv.start()) {
            _available.value = false
            return
        }

        val disco = PeerDiscovery(app).also { discovery = it }
        disco.advertise(displayName(app), SignalingClient.DEFAULT_PORT)
        disco.startDiscovery()

        _available.value = true
    }

    fun goUnavailable() {
        _available.value = false
        discovery?.close()
        discovery = null
        server?.close()
        server = null
    }

    /** Refreshes the peer list without tearing the advertisement down. */
    fun rescan() {
        discovery?.let {
            it.stopDiscovery()
            it.startDiscovery()
        }
    }

    // ------------------------------------------------------------- calls

    fun placeCall(
        app: VcdApp,
        peer: PeerDiscovery.Peer,
        contactId: Long?,
        contactLabel: String? = null,
    ) {
        this.app = app
        if (inCall) return
        pendingLabel = contactLabel
        val s = newSession(app)
        s.placeCall(peer, contactId)
        ringer?.startRingback()
        watchForRingStop()
        VoipCallService.start(app, peer.name)
    }

    private fun onInboundConnection(socket: Socket) {
        val application = app
        if (application == null || inCall) {
            // Busy, or not set up. Closing the socket is an honest "unavailable" — better than
            // accepting a call this device cannot actually take.
            runCatching { socket.close() }
            return
        }
        pendingLabel = null
        scope.launch {
            val s = newSession(application)
            s.onRinging = { callerName ->
                ringer?.startIncoming()
                watchForRingStop()
                // Started here rather than from the UI: the whole point of an incoming call is
                // that it reaches the user when they are not looking at the app.
                VoipCallService.start(application, callerName)
            }
            s.adoptIncoming(socket, defaultContactId)
        }
    }

    /** Stops the ringtone as soon as the call leaves a ringing state, however it leaves it. */
    private fun watchForRingStop() {
        scope.launch {
            state.collect { s ->
                if (s.stage != CallStage.INCOMING && s.stage != CallStage.RINGING_OUT) {
                    ringer?.stop()
                    return@collect
                }
            }
        }
    }

    fun answer() {
        ringer?.stop()
        session?.answer()
    }

    fun decline() {
        ringer?.stop()
        session?.decline()
    }

    fun hangUp() {
        ringer?.stop()
        session?.hangUp()
        session?.close()
        session = null
    }

    fun setMuted(muted: Boolean) = session?.setMuted(muted)

    fun setSpeakerphone(on: Boolean) = session?.setSpeakerphone(on)

    /** Clears a finished call so the dialler comes back. */
    fun dismiss() {
        hangUp()
        _state.value = CallState()
    }

    private fun newSession(app: VcdApp): CallSession {
        session?.close()
        _state.value = CallState()
        historyRecorded = false
        return CallSession(app, scope, displayName(app)) { transform ->
            _state.value = transform(_state.value)
        }.also {
            session = it
            watchForHistory(app)
        }
    }

    @Volatile private var historyRecorded = false

    /**
     * Writes one Recents row per call, on the first terminal state.
     *
     * Guarded because several paths can end a call — the peer hanging up, a timeout, the user, a
     * failure — and more than one of them can fire for the same call. A duplicated row in a call
     * list is a small bug that makes the list untrustworthy, which defeats the point of having one.
     */
    private fun watchForHistory(app: VcdApp) {
        scope.launch {
            state.collect { s ->
                if (s.stage != CallStage.ENDED && s.stage != CallStage.FAILED) return@collect
                if (historyRecorded) return@collect
                historyRecorded = true
                val label = pendingLabel
                runCatching {
                    app.callHistory.insert(
                        CallHistoryEntity(
                            peerName = s.remoteName ?: "Unknown",
                            contactLabel = label,
                            outgoing = s.role == CallRole.CALLER,
                            startedAtEpochMs = s.startedAtMs ?: System.currentTimeMillis(),
                            durationSeconds = s.durationSeconds,
                            ending = (s.ending ?: CallEnding.HUNG_UP).name,
                        )
                    )
                }.onFailure { Log.w(TAG, "could not record call history", it) }
                return@collect
            }
        }
    }

    // ------------------------------------------------------------- identity

    /** What other devices see in their list. Defaults to the handset's own model name. */
    fun displayName(context: Context): String =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultName()

    fun setDisplayName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NAME, name.trim()).apply()
        // Re-advertise so the change is visible to the other devices immediately rather than at
        // the next restart.
        discovery?.let {
            it.stopAdvertising()
            it.advertise(displayName(context), SignalingClient.DEFAULT_PORT)
        }
    }

    private fun defaultName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        return when {
            model.isEmpty() -> "TRINETRA phone"
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isEmpty() -> model
            else -> "$manufacturer $model"
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "CallManager"
    private const val PREFS = "vcd_call"
    private const val KEY_NAME = "display_name"
}
