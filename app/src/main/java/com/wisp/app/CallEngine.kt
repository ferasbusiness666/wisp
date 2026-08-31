package com.wisp.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * One 1:1 call: the peer connection, the local camera/mic and the knobs the UI
 * exposes. Everything here is driven from the main thread by [CallService];
 * WebRTC's own callbacks are handed straight back to it.
 */
class CallEngine(
    context: Context,
    private val events: Events
) {

    interface Events {
        fun onLocalDescription(description: SessionDescription)
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceStateChanged(state: PeerConnection.IceConnectionState)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRenegotiationFailed(reason: String)
    }

    companion object {
        private const val TAG = "WispCall"
        private const val STREAM_ID = "wisp"
        private const val AUDIO_TRACK_ID = "wisp-audio"
        private const val VIDEO_TRACK_ID = "wisp-video"

        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720

        /** Cap the framerate: past ~30fps the extra bits buy nothing on a phone. */
        private const val TARGET_FPS = 30

        /**
         * Deliberately generous ceiling. With MAINTAIN_FRAMERATE the bandwidth
         * estimator drops resolution on its own when the link is poor, and when
         * the mic is muted the audio budget is freed for video — the encoder can
         * only use that headroom if the ceiling is high enough to allow it.
         */
        private const val MAX_VIDEO_BITRATE_BPS = 2_500_000
        private const val MIN_VIDEO_BITRATE_BPS = 150_000
    }

    private val appContext = context.applicationContext
    private val factory: PeerConnectionFactory = RtcCore.factory(appContext)

    /** WebRTC calls back on its own threads; the rest of the app is main-only. */
    private val main = Handler(Looper.getMainLooper())

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private var peerConnection: PeerConnection? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    var localVideoTrack: VideoTrack? = null
        private set
    private var localAudioTrack: AudioTrack? = null

    var remoteVideoTrack: VideoTrack? = null
        private set
    private var remoteAudioTrack: AudioTrack? = null

    private var videoSender: RtpSender? = null
    private var audioSender: RtpSender? = null

    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private var capturing = false
    private var frontCamera = true

    /** Gain applied to the remote audio track: 1.0 = as received, 2.0 = 200%. */
    var remoteVolume: Double = 1.0
        private set

    var micMuted: Boolean = false
        private set

    var cameraEnabled: Boolean = true
        private set

    // ---- lifecycle ---------------------------------------------------------

    fun startLocalMedia() {
        if (localAudioTrack != null) return

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        audioSource = factory.createAudioSource(audioConstraints).also { source ->
            localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, source)
        }

        val capturer = createCameraCapturer()
        if (capturer != null) {
            val helper = SurfaceTextureHelper.create("WispCapture", RtcCore.eglBase.eglBaseContext)
            val source = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, appContext, source.capturerObserver)
            surfaceHelper = helper
            videoSource = source
            videoCapturer = capturer
            localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, source)
            startCapture()
        } else {
            Log.w(TAG, "no camera available; continuing audio-only")
        }
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>): Boolean {
        closePeerConnection()

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // STUN first: only relay through TURN if a direct path can't be found.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            enableImplicitRollback = true
        }

        val connection = factory.createPeerConnection(config, PcObserver()) ?: run {
            Log.e(TAG, "createPeerConnection returned null")
            return false
        }
        peerConnection = connection
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()

        localAudioTrack?.let { audioSender = connection.addTrack(it, listOf(STREAM_ID)) }
        localVideoTrack?.let { videoSender = connection.addTrack(it, listOf(STREAM_ID)) }
        applyVideoEncoding()
        if (micMuted) applyMicMute()
        return true
    }

    fun close() {
        closePeerConnection()
        stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        remoteVideoTrack = null
        remoteAudioTrack = null
    }

    private fun closePeerConnection() {
        videoSender = null
        audioSender = null
        peerConnection?.let { connection ->
            runCatching {
                connection.close()
                connection.dispose()
            }.onFailure { Log.w(TAG, "dispose failed: ${it.message}") }
        }
        peerConnection = null
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
    }

    // ---- negotiation -------------------------------------------------------

    fun createOffer(iceRestart: Boolean = false) {
        val connection = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        connection.createOffer(object : SdpAdapter("createOffer") {
            override fun onCreateSuccess(description: SessionDescription) {
                post { setLocalAndEmit(description) }
            }

            override fun onCreateFailure(error: String?) {
                post { events.onRenegotiationFailed("offer failed: $error") }
            }
        }, constraints)
    }

    fun createAnswer() {
        val connection = peerConnection ?: return
        connection.createAnswer(object : SdpAdapter("createAnswer") {
            override fun onCreateSuccess(description: SessionDescription) {
                post { setLocalAndEmit(description) }
            }

            override fun onCreateFailure(error: String?) {
                post { events.onRenegotiationFailed("answer failed: $error") }
            }
        }, MediaConstraints())
    }

    private fun setLocalAndEmit(description: SessionDescription) {
        val connection = peerConnection ?: return
        connection.setLocalDescription(object : SdpAdapter("setLocal") {
            override fun onSetSuccess() {
                post {
                    applyVideoEncoding()
                    events.onLocalDescription(description)
                }
            }

            override fun onSetFailure(error: String?) {
                post { events.onRenegotiationFailed("setLocalDescription failed: $error") }
            }
        }, description)
    }

    fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onDone: () -> Unit = {}) {
        val connection = peerConnection ?: return
        connection.setRemoteDescription(object : SdpAdapter("setRemote") {
            override fun onSetSuccess() {
                post {
                    remoteDescriptionSet = true
                    pendingRemoteCandidates.forEach { connection.addIceCandidate(it) }
                    pendingRemoteCandidates.clear()
                    onDone()
                }
            }

            override fun onSetFailure(error: String?) {
                post { events.onRenegotiationFailed("setRemoteDescription failed: $error") }
            }
        }, SessionDescription(type, sdp))
    }

    fun addRemoteIceCandidate(mid: String?, index: Int, sdp: String) {
        val candidate = IceCandidate(mid, index, sdp)
        val connection = peerConnection
        if (connection == null || !remoteDescriptionSet) {
            pendingRemoteCandidates += candidate
            return
        }
        connection.addIceCandidate(candidate)
    }

    /** True when the peer connection exists and can be renegotiated in place. */
    fun canRestartIce(): Boolean = peerConnection != null && remoteDescriptionSet

    // ---- controls ----------------------------------------------------------

    /**
     * Muting detaches the track from the sender instead of just silencing it, so
     * WebRTC stops paying for an audio stream at all and hands that share of the
     * bandwidth budget to video.
     */
    fun setMicMuted(muted: Boolean) {
        micMuted = muted
        applyMicMute()
    }

    private fun applyMicMute() {
        val track = localAudioTrack ?: return
        track.setEnabled(!micMuted)
        audioSender?.setTrack(if (micMuted) null else track, /* takeOwnership = */ false)
    }

    fun setCameraEnabled(enabled: Boolean) {
        cameraEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
        if (enabled) startCapture() else stopCapture()
    }

    fun switchCamera() {
        val capturer = videoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                frontCamera = isFrontFacing
            }

            override fun onCameraSwitchError(error: String?) {
                Log.w(TAG, "camera switch failed: $error")
            }
        })
    }

    fun isFrontCamera(): Boolean = frontCamera

    /**
     * Applies a gain directly on the remote WebRTC audio track rather than
     * touching the system stream, which lets playback go above what the
     * in-call volume floor would otherwise allow.
     */
    fun setRemoteVolume(volume: Double) {
        remoteVolume = volume.coerceIn(0.0, 2.0)
        remoteAudioTrack?.setVolume(remoteVolume)
    }

    // ---- internals ---------------------------------------------------------

    private fun applyVideoEncoding() {
        val sender = videoSender ?: return
        val parameters = sender.parameters ?: return
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        parameters.encodings.forEach { encoding ->
            encoding.maxBitrateBps = MAX_VIDEO_BITRATE_BPS
            encoding.minBitrateBps = MIN_VIDEO_BITRATE_BPS
            encoding.maxFramerate = TARGET_FPS
        }
        runCatching { sender.setParameters(parameters) }
            .onFailure { Log.w(TAG, "could not apply encoding params: ${it.message}") }
    }

    private fun startCapture() {
        val capturer = videoCapturer ?: return
        if (capturing) return
        runCatching { capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, TARGET_FPS) }
            .onSuccess { capturing = true }
            .onFailure { Log.w(TAG, "startCapture failed: ${it.message}") }
    }

    private fun stopCapture() {
        val capturer = videoCapturer ?: return
        if (!capturing) return
        runCatching { capturer.stopCapture() }
            .onFailure { Log.w(TAG, "stopCapture failed: ${it.message}") }
        capturing = false
    }

    private fun createCameraCapturer(): CameraVideoCapturer? = try {
        openCamera()
    } catch (error: Throwable) {
        // Some devices throw out of the camera service rather than reporting an
        // empty list. Audio-only is a far better outcome than no call at all.
        Log.w(TAG, "camera unavailable: ${error.message}")
        null
    }

    private fun openCamera(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val back = names.firstOrNull { enumerator.isBackFacing(it) }
        val chosen = front ?: back ?: names.firstOrNull() ?: return null
        frontCamera = front != null && chosen == front
        return enumerator.createCapturer(chosen, null)
    }

    private inner class PcObserver : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) {
            post { events.onLocalIceCandidate(candidate) }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ice state = $state")
            post { events.onIceStateChanged(state) }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            when (val track = transceiver.receiver?.track()) {
                is VideoTrack -> post {
                    remoteVideoTrack = track
                    events.onRemoteVideoTrack(track)
                }
                is AudioTrack -> post {
                    remoteAudioTrack = track
                    // Re-apply whatever the slider was left on for this call.
                    track.setVolume(remoteVolume)
                }
                else -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) = Unit
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = Unit
        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onRemoveTrack(receiver: RtpReceiver) = Unit
    }

    private open inner class SdpAdapter(private val label: String) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "$label create failed: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.w(TAG, "$label set failed: $error")
        }
    }
}
