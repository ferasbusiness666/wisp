package com.wisp.app

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Owns the signaling socket and the current call for the whole process.
 *
 * It stays foreground for as long as the app is alive so an incoming offer can
 * ring the phone, and it is the only component that mutates [CallStateHolder].
 */
class CallService : Service(), SignalingClient.Listener, CallEngine.Events {

    companion object {
        private const val TAG = "WispService"

        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            26 // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31)
        )

        /** Most private first: whatever is on your head, then the earpiece. */
        private val PRIVATE_ROUTE_ORDER = listOf(
            26, // TYPE_BLE_HEADSET
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        )

        const val ACTION_START = "com.wisp.app.START"
        const val ACTION_CALL = "com.wisp.app.CALL"
        const val ACTION_ANSWER = "com.wisp.app.ANSWER"
        const val ACTION_DECLINE = "com.wisp.app.DECLINE"
        const val ACTION_HANGUP = "com.wisp.app.HANGUP"
        const val ACTION_REFRESH_CONTACTS = "com.wisp.app.REFRESH_CONTACTS"
        const val ACTION_RENAME = "com.wisp.app.RENAME"
        const val ACTION_REFRESH_DIRECTORY = "com.wisp.app.REFRESH_DIRECTORY"
        const val ACTION_ADD_ME = "com.wisp.app.ADD_ME"

        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"

        /**
         * Media can be transiently down for a long time on a train or in a lift;
         * ICE DISCONNECTED is not a hangup, it is a hint to start trying harder.
         */
        private const val GRACE_MS = 90_000L
        private const val ICE_RESTART_FIRST_DELAY_MS = 2_000L
        private const val ICE_RESTART_RETRY_MS = 8_000L
        private const val RING_TIMEOUT_MS = 45_000L
        private const val SIGNALING_WAIT_MS = 10_000L

        @Volatile
        var instance: CallService? = null
            private set

        fun ensureRunning(context: Context) {
            start(context, Intent(context, CallService::class.java).setAction(ACTION_START))
        }

        fun placeCall(context: Context, code: String, name: String) {
            start(
                context,
                Intent(context, CallService::class.java)
                    .setAction(ACTION_CALL)
                    .putExtra(EXTRA_CODE, code)
                    .putExtra(EXTRA_NAME, name)
            )
        }

        fun answer(context: Context) = command(context, ACTION_ANSWER)

        fun hangUp(context: Context) = command(context, ACTION_HANGUP)

        fun refreshContacts(context: Context) = command(context, ACTION_REFRESH_CONTACTS)

        /** Republishes our display name after the user edits it. */
        fun republishName(context: Context) = command(context, ACTION_RENAME)

        /** Re-reads who is online, e.g. when the home screen comes back. */
        fun refreshDirectory(context: Context) = command(context, ACTION_REFRESH_DIRECTORY)

        /** Tells someone we just saved to save us back. */
        fun askToBeAdded(context: Context, code: String) {
            start(
                context,
                Intent(context, CallService::class.java)
                    .setAction(ACTION_ADD_ME)
                    .putExtra(EXTRA_CODE, code)
            )
        }

        private fun command(context: Context, action: String) {
            start(context, Intent(context, CallService::class.java).setAction(action))
        }

        private fun start(context: Context, intent: Intent) {
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start service: ${it.message}") }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Setting up a call touches the camera, the microphone, the codec factory
     * and the network, any of which can fail on a given device. Without this,
     * such a failure leaves the coroutine and takes the whole app down; with it
     * the call ends with a message the caller can actually read.
     */
    private val crashGuard = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "call setup failed", error)
        handler.post { endCall(getString(R.string.status_failed), notifyPeer = true) }
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)

    private lateinit var myCode: String
    private lateinit var signaling: SignalingClient

    private var engine: CallEngine? = null
    private var phase = CallPhase.IDLE
    private var peerCode: String? = null
    private var peerName: String? = null
    private var isCaller = false
    private var pendingRemoteOffer: String? = null
    private var pendingOutgoingCall: Pair<String, String>? = null

    private var ringtone: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkId: Long? = null

    // ---- lifecycle ---------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        myCode = DeviceIdentity.myCode(this)
        Notifications.createChannels(this)
        publish { it.copy(volumePercent = Prefs.of(this).getInt("volume_percent", 100)) }
        goForeground(Notifications.idle(this), inCall = false)

        signaling = SignalingClient(myCode, this)
        signaling.watch(ContactStore.codes(this))
        signaling.identify(DeviceIdentity.myName(this))
        signaling.connect()

        registerNetworkCallback()
        Log.i(TAG, "service created as $myCode")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CALL -> {
                val code = intent.getStringExtra(EXTRA_CODE)
                val name = intent.getStringExtra(EXTRA_NAME) ?: code
                if (code != null && name != null) startOutgoing(code, name)
            }
            ACTION_ANSWER -> answerIncoming()
            ACTION_DECLINE -> endCall(getString(R.string.status_declined), notifyPeer = true)
            ACTION_HANGUP -> endCall(getString(R.string.status_call_ended), notifyPeer = true)
            ACTION_REFRESH_CONTACTS -> signaling.watch(ContactStore.codes(this))
            ACTION_RENAME -> signaling.identify(DeviceIdentity.myName(this))
            ACTION_REFRESH_DIRECTORY -> {
                if (signaling.isOpen) signaling.requestDirectory() else signaling.reconnectNow()
            }
            ACTION_ADD_ME -> intent.getStringExtra(EXTRA_CODE)?.let { signaling.sendAddMe(it) }
            else -> {
                // Plain start (launcher or boot): just make sure we are connected.
                if (!signaling.isOpen) signaling.reconnectNow()
            }
        }
        // Restart us if the system kills the process; the socket is the whole point.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        endCall(null, notifyPeer = true)
        signaling.disconnect()
        unregisterNetworkCallback()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    // ---- outgoing ----------------------------------------------------------

    private fun startOutgoing(code: String, name: String) {
        if (phase != CallPhase.IDLE) {
            Log.w(TAG, "ignoring call to $code: already in phase $phase")
            return
        }
        peerCode = code
        peerName = name
        isCaller = true
        setPhase(CallPhase.OUTGOING)
        publish { it.copy(peerCode = code, peerName = name, endReason = null, connectedAtMs = 0L) }
        acquireCallResources()

        // Never fire an offer into a dead socket: reconnect first, then send.
        if (!signaling.isOpen) {
            Log.i(TAG, "signaling down; reconnecting before dialing")
            pendingOutgoingCall = code to name
            signaling.reconnectNow()
            handler.postDelayed(signalingWaitTimeout, SIGNALING_WAIT_MS)
            return
        }
        beginOutgoingNegotiation()
    }

    private val signalingWaitTimeout: Runnable = Runnable {
        if (pendingOutgoingCall != null) {
            pendingOutgoingCall = null
            endCall(getString(R.string.status_no_connection), notifyPeer = false)
        }
    }

    private fun beginOutgoingNegotiation() {
        val target = peerCode ?: return
        scope.launch {
            CrashLog.step(this@CallService, "outgoing: fetching ice servers")
            val iceServers = IceServers.fetch(myCode)
            if (phase != CallPhase.OUTGOING || peerCode != target) return@launch
            val call = newEngine(iceServers) ?: return@launch
            call.createOffer()
            handler.postDelayed(ringTimeout, RING_TIMEOUT_MS)
        }
    }

    private val ringTimeout: Runnable = Runnable {
        if (phase == CallPhase.OUTGOING || phase == CallPhase.INCOMING) {
            endCall(getString(R.string.status_no_answer), notifyPeer = true)
        }
    }

    // ---- incoming ----------------------------------------------------------

    private fun answerIncoming() {
        if (phase != CallPhase.INCOMING) return
        val offer = pendingRemoteOffer ?: return
        val from = peerCode ?: return
        stopRinging()
        Notifications.cancelIncoming(this)
        setPhase(CallPhase.CONNECTING)
        acquireCallResources()

        scope.launch {
            CrashLog.step(this@CallService, "answering: fetching ice servers")
            val iceServers = IceServers.fetch(myCode)
            if (phase != CallPhase.CONNECTING || peerCode != from) return@launch
            val call = newEngine(iceServers) ?: return@launch
            call.setRemoteDescription(SessionDescription.Type.OFFER, offer) {
                pendingRemoteOffer = null
                call.createAnswer()
            }
        }
    }

    private fun newEngine(iceServers: List<PeerConnection.IceServer>): CallEngine? {
        CrashLog.step(this, "creating video engine")
        val call = try {
            (engine ?: CallEngine(this, this).also {
                engine = it
                CallStateHolder.engine = it
            }).apply {
                CrashLog.step(this@CallService, "opening camera and microphone")
                startLocalMedia()
            }
        } catch (error: Throwable) {
            // Creating the WebRTC factory, opening the camera or opening the
            // microphone can all fail per-device. None of them should crash.
            Log.e(TAG, "could not start local media", error)
            endCall(getString(R.string.status_no_media), notifyPeer = true)
            return null
        }

        CrashLog.step(this, "creating peer connection")
        val ready = try {
            call.createPeerConnection(iceServers)
        } catch (error: Throwable) {
            Log.e(TAG, "could not create peer connection", error)
            false
        }
        if (!ready) {
            endCall(getString(R.string.status_failed), notifyPeer = true)
            return null
        }
        call.setRemoteVolume(CallStateHolder.call.value.volumePercent / 100.0)
        publish {
            it.copy(
                micMuted = call.micMuted,
                cameraOn = call.cameraEnabled,
                frontCamera = call.isFrontCamera(),
                mediaVersion = it.mediaVersion + 1
            )
        }
        CrashLog.stepDone(this)
        return call
    }

    // ---- signaling callbacks ----------------------------------------------

    override fun onSignalingOpen() {
        CallStateHolder.setSignalingOnline(true)
        pendingOutgoingCall?.let { (code, _) ->
            pendingOutgoingCall = null
            handler.removeCallbacks(signalingWaitTimeout)
            if (phase == CallPhase.OUTGOING && peerCode == code) beginOutgoingNegotiation()
        }
        // A socket that just came back may have missed the peer's ICE; if media
        // is not up, nudge it.
        if (phase == CallPhase.RECONNECTING) scheduleIceRestart(0L)
    }

    override fun onSignalingClosed() {
        CallStateHolder.setSignalingOnline(false)
        CallStateHolder.setPresence(emptyMap())
        // Nothing is known to be online while the socket is down.
        CallStateHolder.setDirectory(emptyList())
    }

    override fun onPresence(states: Map<String, Boolean>) {
        CallStateHolder.mergePresence(states)
    }

    override fun onAddMe(code: String, name: String) {
        if (code == myCode) return
        // They saved us, so save them: a contact list that only works one way
        // is a trap, since the other side looks reachable and isn't.
        if (ContactStore.add(this, Contact(code, name))) {
            Log.i(TAG, "added $code back after they added us")
            signaling.watch(ContactStore.codes(this))
            CallStateHolder.bumpContacts()
        }
    }

    override fun onDirectory(people: List<Contact>) {
        // Drop ourselves; nobody needs to add their own phone.
        CallStateHolder.setDirectory(people.filterNot { it.code == myCode })
    }

    override fun onOffer(from: String, sdp: String) {
        when {
            // Renegotiation (an ICE restart) from the peer we are already talking to.
            peerCode == from && phase in setOf(
                CallPhase.ACTIVE, CallPhase.CONNECTING, CallPhase.RECONNECTING
            ) -> {
                val call = engine ?: return
                call.setRemoteDescription(SessionDescription.Type.OFFER, sdp) { call.createAnswer() }
            }

            // Glare: we both dialled at once. The lower code keeps its offer, the
            // other side drops its own and answers — both ends agree without a
            // round trip.
            phase == CallPhase.OUTGOING && peerCode == from -> {
                if (myCode < from) {
                    Log.i(TAG, "glare with $from: keeping our offer")
                } else {
                    Log.i(TAG, "glare with $from: yielding to their offer")
                    engine?.close()
                    engine = null
                    CallStateHolder.engine = null
                    isCaller = false
                    pendingRemoteOffer = sdp
                    setPhase(CallPhase.CONNECTING)
                    answerAfterYield()
                }
            }

            phase != CallPhase.IDLE -> signaling.sendBye(from, "busy")

            else -> ring(from, sdp)
        }
    }

    private fun answerAfterYield() {
        val offer = pendingRemoteOffer ?: return
        val from = peerCode ?: return
        scope.launch {
            val iceServers = IceServers.fetch(myCode)
            if (peerCode != from) return@launch
            val call = newEngine(iceServers) ?: return@launch
            call.setRemoteDescription(SessionDescription.Type.OFFER, offer) {
                pendingRemoteOffer = null
                call.createAnswer()
            }
        }
    }

    private fun ring(from: String, sdp: String) {
        peerCode = from
        peerName = ContactStore.find(this, from)?.name ?: from
        isCaller = false
        pendingRemoteOffer = sdp
        setPhase(CallPhase.INCOMING)
        publish { it.copy(peerCode = from, peerName = peerName, endReason = null, connectedAtMs = 0L) }

        val notification = Notifications.incoming(this, peerName ?: from, from)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { NotificationManagerCompat.from(this).notify(Notifications.ID_INCOMING, notification) }
                .onFailure { Log.w(TAG, "could not post incoming notification: ${it.message}") }
        } else {
            // Notifications are off: the ringtone below plus the call screen the
            // user reaches from the app are all we have.
            Log.w(TAG, "notifications not permitted; ringing without a notification")
        }

        if (!Notifications.canUseFullScreenIntent(this)) {
            // Without the Android 14 grant the notification above is only a
            // heads-up banner, so the call still surfaces — just not full screen.
            Log.i(TAG, "full-screen intents not granted; degrading to heads-up")
        }
        startRinging()
        handler.postDelayed(ringTimeout, RING_TIMEOUT_MS)
    }

    override fun onAnswer(from: String, sdp: String) {
        if (from != peerCode) return
        val call = engine ?: return
        handler.removeCallbacks(ringTimeout)
        if (phase == CallPhase.OUTGOING) setPhase(CallPhase.CONNECTING)
        call.setRemoteDescription(SessionDescription.Type.ANSWER, sdp)
    }

    override fun onRemoteIce(from: String, mid: String?, index: Int, candidate: String) {
        if (from != peerCode) return
        engine?.addRemoteIceCandidate(mid, index, candidate)
    }

    override fun onBye(from: String, reason: String?) {
        if (from != peerCode) return
        endCall(getString(R.string.status_call_ended), notifyPeer = false)
    }

    override fun onIceRestartRequest(from: String) {
        if (from != peerCode) return
        // Only the side that made the original offer renegotiates, so the two
        // ends can never collide on a restart.
        if (isCaller) doIceRestart()
    }

    override fun onPeerUnavailable(code: String, reason: String?) {
        if (code != peerCode) return
        if (phase == CallPhase.OUTGOING) {
            endCall(getString(R.string.status_offline), notifyPeer = false)
        }
    }

    // ---- engine callbacks --------------------------------------------------

    override fun onLocalDescription(description: SessionDescription) {
        val target = peerCode ?: return
        when (description.type) {
            SessionDescription.Type.OFFER -> signaling.sendOffer(target, description.description)
            SessionDescription.Type.ANSWER -> signaling.sendAnswer(target, description.description)
            else -> Unit
        }
    }

    override fun onLocalIceCandidate(candidate: IceCandidate) {
        val target = peerCode ?: return
        signaling.sendIce(target, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
    }

    override fun onIceStateChanged(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> onMediaUp()

            PeerConnection.IceConnectionState.DISCONNECTED -> onMediaDown(immediateRestart = false)

            // FAILED means this ICE generation is dead; a restart is the only way
            // back, but the call itself still gets the full grace window.
            PeerConnection.IceConnectionState.FAILED -> onMediaDown(immediateRestart = true)

            PeerConnection.IceConnectionState.CLOSED ->
                if (phase != CallPhase.IDLE) endCall(getString(R.string.status_call_ended), notifyPeer = false)

            else -> Unit
        }
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        // The activity reads the track from the engine; bumping the version is
        // what tells it there is a new one to attach.
        Log.i(TAG, "remote video track ${track.id()}")
        publish { it.copy(mediaVersion = it.mediaVersion + 1) }
    }

    override fun onRemoteMediaState(micOn: Boolean, cameraOn: Boolean) {
        publish { it.copy(remoteMicOn = micOn, remoteCameraOn = cameraOn) }
    }

    override fun onRenegotiationFailed(reason: String) {
        Log.w(TAG, "renegotiation failed: $reason")
        if (phase == CallPhase.OUTGOING || phase == CallPhase.CONNECTING) {
            endCall(getString(R.string.status_failed), notifyPeer = true)
        }
    }

    // ---- drop protection ---------------------------------------------------

    private fun onMediaUp() {
        handler.removeCallbacks(graceExpired)
        handler.removeCallbacks(iceRestartTick)
        handler.removeCallbacks(ringTimeout)
        val firstTime = CallStateHolder.call.value.connectedAtMs == 0L
        setPhase(CallPhase.ACTIVE)
        if (firstTime) {
            publish { it.copy(connectedAtMs = SystemClock.elapsedRealtime()) }
        }
        updateOngoingNotification()
    }

    private fun onMediaDown(immediateRestart: Boolean) {
        if (phase == CallPhase.IDLE || phase == CallPhase.INCOMING || phase == CallPhase.OUTGOING) return
        if (phase != CallPhase.RECONNECTING) {
            setPhase(CallPhase.RECONNECTING)
            // One grace window per drop, not per state change.
            handler.postDelayed(graceExpired, GRACE_MS)
        }
        updateOngoingNotification()
        scheduleIceRestart(if (immediateRestart) 0L else ICE_RESTART_FIRST_DELAY_MS)
    }

    private fun scheduleIceRestart(delayMs: Long) {
        handler.removeCallbacks(iceRestartTick)
        handler.postDelayed(iceRestartTick, delayMs)
    }

    private val iceRestartTick: Runnable = Runnable {
        if (phase != CallPhase.RECONNECTING) return@Runnable
        if (!signaling.isOpen) {
            // No path to renegotiate over yet — get the socket back first.
            signaling.reconnectNow()
        } else {
            doIceRestart()
        }
        handler.postDelayed(iceRestartTick, ICE_RESTART_RETRY_MS)
    }

    private fun doIceRestart() {
        val call = engine ?: return
        val target = peerCode ?: return
        if (!signaling.isOpen) return
        if (isCaller) {
            if (call.canRestartIce()) {
                Log.i(TAG, "triggering ICE restart")
                call.createOffer(iceRestart = true)
            }
        } else {
            Log.i(TAG, "asking $target for an ICE restart")
            signaling.requestIceRestart(target)
        }
    }

    private val graceExpired: Runnable = Runnable {
        if (phase == CallPhase.RECONNECTING) {
            // Our timer ran out. Tell the other side if we still can; if we
            // can't, their identical timer lands them in the same place.
            endCall(getString(R.string.status_connection_lost), notifyPeer = true)
        }
    }

    // ---- network switching -------------------------------------------------

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { onNetworkChanged(network) }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (lastNetworkId == network.networkHandle) lastNetworkId = null
                    CallStateHolder.setSignalingOnline(false)
                }
            }
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "network callback registration failed: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun onNetworkChanged(network: Network) {
        val id = network.networkHandle
        val changed = lastNetworkId != null && lastNetworkId != id
        lastNetworkId = id
        if (!signaling.isOpen) signaling.reconnectNow()
        if (!changed) return

        Log.i(TAG, "network changed (WiFi <-> cellular?)")
        if (phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING || phase == CallPhase.CONNECTING) {
            // We already know the path is gone: don't sit through the grace timer
            // waiting for ICE to work it out, renegotiate now.
            handler.postDelayed({ if (phase != CallPhase.IDLE) doIceRestart() }, 500L)
        }
    }

    // ---- controls used by the UI ------------------------------------------

    fun toggleMic(): Boolean {
        val call = engine ?: return false
        call.setMicMuted(!call.micMuted)
        publish { it.copy(micMuted = call.micMuted) }
        return call.micMuted
    }

    fun toggleCamera(): Boolean {
        val call = engine ?: return true
        call.setCameraEnabled(!call.cameraEnabled)
        publish { it.copy(cameraOn = call.cameraEnabled) }
        return call.cameraEnabled
    }

    fun switchCamera() {
        engine?.switchCamera { front -> publish { it.copy(frontCamera = front) } }
    }

    fun toggleSpeaker() {
        applySpeaker(!CallStateHolder.call.value.speakerOn)
    }

    /** Volume keys step Wisp's own gain, which can go past the system's 100%. */
    fun nudgeVolume(delta: Int) {
        setVolumePercent(CallStateHolder.call.value.volumePercent + delta)
    }

    fun collectStats(onResult: (String) -> Unit) {
        val call = engine ?: run {
            onResult("Not in a call")
            return
        }
        call.collectStats(onResult)
    }

    fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        engine?.setRemoteVolume(clamped / 100.0)
        publish { it.copy(volumePercent = clamped) }
        Prefs.of(this).edit().putInt("volume_percent", clamped).apply()
    }

    // ---- teardown ----------------------------------------------------------

    private fun endCall(reason: String?, notifyPeer: Boolean) {
        val target = peerCode
        if (notifyPeer && target != null && signaling.isOpen && phase != CallPhase.IDLE) {
            signaling.sendBye(target, reason ?: "ended")
        }

        handler.removeCallbacks(graceExpired)
        handler.removeCallbacks(iceRestartTick)
        handler.removeCallbacks(ringTimeout)
        handler.removeCallbacks(signalingWaitTimeout)
        pendingOutgoingCall = null
        pendingRemoteOffer = null

        stopRinging()
        Notifications.cancelIncoming(this)
        CrashLog.stepDone(this)

        engine?.close()
        engine = null
        CallStateHolder.engine = null

        releaseCallResources()
        peerCode = null
        peerName = null
        isCaller = false
        phase = CallPhase.IDLE
        publish {
            CallUi(
                phase = CallPhase.IDLE,
                volumePercent = it.volumePercent,
                endReason = reason
            )
        }
        goForeground(Notifications.idle(this), inCall = false)
    }

    // ---- plumbing ----------------------------------------------------------

    private fun setPhase(next: CallPhase) {
        if (phase == next) return
        phase = next
        publish { it.copy(phase = next) }
        if (next == CallPhase.CONNECTING || next == CallPhase.ACTIVE || next == CallPhase.RECONNECTING) {
            updateOngoingNotification()
        }
    }

    private fun publish(block: (CallUi) -> CallUi) {
        CallStateHolder.update(block)
    }

    private fun updateOngoingNotification() {
        val name = peerName ?: return
        val status = when (phase) {
            CallPhase.RECONNECTING -> getString(R.string.status_reconnecting)
            CallPhase.CONNECTING -> getString(R.string.status_connecting)
            else -> getString(R.string.status_in_call)
        }
        goForeground(Notifications.ongoing(this, name, status), inCall = true)
    }

    /**
     * The service runs as `specialUse` while it is only holding the socket and
     * escalates to `camera|microphone` for the duration of a call, which is what
     * Android 14+ requires before the call can touch either device.
     */
    private fun goForeground(notification: Notification, inCall: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching { startForeground(Notifications.ID_SERVICE, notification) }
            return
        }

        val type = foregroundServiceType(inCall)
        val result = runCatching { startForeground(Notifications.ID_SERVICE, notification, type) }
        if (result.isSuccess) return

        // The camera/microphone type is refused if we are not visible to the
        // user; an untyped foreground service still keeps the process alive.
        Log.w(TAG, "FGS type $type refused: ${result.exceptionOrNull()?.message}")
        val fallback = foregroundServiceType(inCall = false)
        runCatching { startForeground(Notifications.ID_SERVICE, notification, fallback) }
            .onFailure { Log.w(TAG, "untyped foreground start failed: ${it.message}") }
    }

    /**
     * Types were added to the platform over several releases, and passing one
     * an older Android doesn't know throws, so each range gets what it accepts:
     * camera/microphone from 30, specialUse from 34, nothing before that.
     */
    private fun foregroundServiceType(inCall: Boolean): Int = when {
        inCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        !inCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        else -> 0
    }

    private fun acquireCallResources() {
        val audio = getSystemService(AudioManager::class.java)
        if (audio != null) {
            previousAudioMode = audio.mode
            @Suppress("DEPRECATION")
            previousSpeakerphone = audio.isSpeakerphoneOn
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            // Loudspeaker by default, like any video call, unless headphones are
            // already plugged in or paired: then the call goes to them.
            applySpeaker(!privateAudioConnected(audio))
        }
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wisp:call")?.apply {
                setReferenceCounted(false)
                runCatching { acquire(2 * 60 * 60 * 1000L) }
            }
        }
    }

    private fun privateAudioConnected(audio: AudioManager): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }

    /**
     * Routes call audio to the loudspeaker, or to the most private output there
     * is: a Bluetooth or wired headset if connected, otherwise the earpiece.
     */
    private fun applySpeaker(on: Boolean) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val target = if (on) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                PRIVATE_ROUTE_ORDER.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
            }
            if (target != null) {
                runCatching { audio.setCommunicationDevice(target) }
            } else {
                audio.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = on
        }
        publish { it.copy(speakerOn = on) }
    }

    private fun releaseCallResources() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        val audio = getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = previousSpeakerphone
        audio.mode = previousAudioMode
    }

    private fun startRinging() {
        val audio = getSystemService(AudioManager::class.java)
        val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            runCatching {
                ringtone = MediaPlayer().apply {
                    setDataSource(this@CallService, uri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            }.onFailure { Log.w(TAG, "ringtone failed: ${it.message}") }
        }

        if (mode != AudioManager.RINGER_MODE_SILENT) {
            vibrator = currentVibrator()
            val pattern = longArrayOf(0, 700, 700)
            runCatching {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            }.onFailure { Log.w(TAG, "vibrate failed: ${it.message}") }
        }
    }

    private fun stopRinging() {
        ringtone?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
        ringtone = null
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
    }

    private fun currentVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
}
