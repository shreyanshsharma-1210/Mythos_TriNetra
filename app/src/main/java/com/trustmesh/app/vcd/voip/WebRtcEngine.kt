package com.trustmesh.app.vcd.voip

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.Closeable

/**
 * Thin wrapper over the WebRTC peer connection.
 *
 * Its one non-obvious job is [onRemoteAudio]: when the far end's audio track arrives, the sink is
 * attached to it immediately, which is what gives the ML pipeline decoded PCM before playback.
 * Everything else here is the standard offer/answer dance.
 *
 * Kept free of any knowledge of the ML pipeline, signalling transport or UI, so a different audio
 * source can be dropped in later without touching this file.
 */
class WebRtcEngine(private val context: Context) : Closeable {

    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnectionChange(state: PeerConnection.PeerConnectionState)
        fun onRemoteAudioTrack(track: AudioTrack)
        fun onFailure(message: String)
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var localSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var remoteTrack: AudioTrack? = null

    var listener: Listener? = null

    /** Sink attached to the remote track the moment it appears. */
    var remoteSink: RemoteAudioAdapter? = null

    fun start(iceServers: List<PeerConnection.IceServer> = emptyList()) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        // Hardware AEC and NS are left on. They act on this device's *outgoing* microphone audio,
        // not on the incoming track the models analyse, so switching them off would only degrade
        // what the other party hears while changing nothing we measure.
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(msg: String) = fail("audio playout init: $msg")
                override fun onWebRtcAudioTrackStartError(
                    code: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                    msg: String,
                ) = fail("audio playout start: $msg")

                override fun onWebRtcAudioTrackError(msg: String) = fail("audio playout: $msg")
            })
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(msg: String) = fail("mic init: $msg")
                override fun onWebRtcAudioRecordStartError(
                    code: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    msg: String,
                ) = fail("mic start: $msg")

                override fun onWebRtcAudioRecordError(msg: String) = fail("mic: $msg")
            })
            .createAudioDeviceModule()
        audioDeviceModule = adm

        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        factory = f

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = f.createPeerConnection(config, observer)
            ?: run {
                fail("Could not create the peer connection.")
                return
            }

        localSource = f.createAudioSource(MediaConstraints())
        localTrack = f.createAudioTrack(LOCAL_TRACK_ID, localSource).also { track ->
            peerConnection?.addTrack(track, listOf(STREAM_ID))
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        localTrack?.setEnabled(enabled)
    }

    /** Silences playout without stopping the track, so analysis continues while the user listens. */
    fun setPlayoutVolume(volume: Double) {
        remoteTrack?.setVolume(volume)
    }

    fun createOffer(onSdp: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return fail("No peer connection to offer from.")
        pc.createOffer(
            sdpObserver("createOffer") { sdp ->
                pc.setLocalDescription(sdpObserver("setLocal(offer)") {}, sdp)
                onSdp(sdp)
            },
            MediaConstraints(),
        )
    }

    fun createAnswer(onSdp: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return fail("No peer connection to answer from.")
        pc.createAnswer(
            sdpObserver("createAnswer") { sdp ->
                pc.setLocalDescription(sdpObserver("setLocal(answer)") {}, sdp)
                onSdp(sdp)
            },
            MediaConstraints(),
        )
    }

    fun setRemoteDescription(sdp: SessionDescription, onDone: () -> Unit = {}) {
        val pc = peerConnection ?: return fail("No peer connection for the remote description.")
        pc.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) = Unit
                override fun onSetSuccess() = onDone()
                override fun onCreateFailure(p0: String?) = fail("setRemoteDescription: $p0")
                override fun onSetFailure(p0: String?) = fail("setRemoteDescription: $p0")
            },
            sdp,
        )
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            listener?.onLocalIceCandidate(candidate)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "peer connection state: $newState")
            listener?.onConnectionChange(newState)
        }

        /**
         * Unified Plan delivers the remote track here. Attaching the sink at this exact point is
         * what makes the whole module work — the audio has been decoded and has not yet been mixed
         * for playout.
         */
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver?.track()
            if (track is AudioTrack) attachRemote(track)
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            // Older code paths and some builds fire this instead of onTrack. Attaching twice is
            // harmless because attachRemote ignores a track it has already seen.
            (receiver.track() as? AudioTrack)?.let(::attachRemote)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun attachRemote(track: AudioTrack) {
        if (remoteTrack === track) return
        remoteTrack = track
        remoteSink?.let { sink ->
            runCatching { track.addSink(sink) }
                .onFailure { fail("Could not tap the remote audio track: ${it.message}") }
                .onSuccess { Log.i(TAG, "remote audio sink attached to ${track.id()}") }
        }
        listener?.onRemoteAudioTrack(track)
    }

    private fun sdpObserver(what: String, onCreated: (SessionDescription) -> Unit) =
        object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = onCreated(sdp)
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) = fail("$what: $error")
            override fun onSetFailure(error: String?) = fail("$what: $error")
        }

    private fun fail(message: String) {
        Log.e(TAG, message)
        listener?.onFailure(message)
    }

    override fun close() {
        remoteSink?.let { sink -> runCatching { remoteTrack?.removeSink(sink) } }
        remoteTrack = null
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { localTrack?.dispose() }
        localTrack = null
        runCatching { localSource?.dispose() }
        localSource = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null
    }

    private companion object {
        const val TAG = "WebRtcEngine"
        const val LOCAL_TRACK_ID = "vcd-local-audio"
        const val STREAM_ID = "vcd-stream"
    }

    /** Track state helper used by diagnostics. */
    val remoteTrackState: MediaStreamTrack.State?
        get() = remoteTrack?.state()
}
