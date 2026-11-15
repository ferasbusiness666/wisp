package com.wisp.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wisp.app.databinding.ActivityCallBinding
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import kotlin.math.hypot

/**
 * Full-screen call UI, laid out like Instagram's: the other person fills the
 * screen, your own camera floats in a rounded corner you can drag or tap to
 * swap, and the controls sit on a dark bar that hides when you tap the video.
 *
 * While calling or ringing, your own camera fills the screen behind their
 * avatar instead. All call state comes from the service via [CallStateHolder].
 */
class CallActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PEER_CODE = "peer_code"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_AUTO_ANSWER = "auto_answer"

        /** How long to wait for the service to report a phase before giving up. */
        private const val START_GRACE_MS = 12_000L
        private const val CONTROLS_HIDE_MS = 4_000L
        private const val VOLUME_HIDE_MS = 2_500L
        private const val NO_VIDEO_HINT_MS = 8_000L
        private const val FADE_MS = 180L

        fun outgoingIntent(context: Context, code: String, name: String): Intent =
            base(context).putExtra(EXTRA_PEER_CODE, code).putExtra(EXTRA_PEER_NAME, name)

        fun incomingIntent(context: Context, code: String, name: String): Intent =
            base(context).putExtra(EXTRA_PEER_CODE, code).putExtra(EXTRA_PEER_NAME, name)

        /** Used by the notification's Answer action, which must go via the UI. */
        fun answerIntent(context: Context, code: String, name: String): Intent =
            base(context)
                .putExtra(EXTRA_PEER_CODE, code)
                .putExtra(EXTRA_PEER_NAME, name)
                .putExtra(EXTRA_AUTO_ANSWER, true)

        fun ongoingIntent(context: Context): Intent = base(context)

        private fun base(context: Context): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private lateinit var binding: ActivityCallBinding
    private val handler = Handler(Looper.getMainLooper())

    private var renderersReady = false
    private var fullTrack: VideoTrack? = null
    private var pipTrack: VideoTrack? = null

    /** The remote track whose first frame we are waiting on, and whether it came. */
    private var watchedRemote: VideoTrack? = null
    private var remoteFrameSeen = false

    /** True when the user tapped the self-view to put themselves in the big view. */
    private var swapped = false
    private var controlsShown = true
    private var liveSinceMs = 0L

    private var autoAnswer = false
    private var finishing = false
    private var intentName: String? = null
    private var intentCode: String? = null
    private var lastState = CallUi()

    private var insetBottom = 0
    private var trackingVolume = false

    /**
     * The service starts asynchronously, so for the first moments after we are
     * launched the shared state can still read IDLE. Finishing on that would
     * close the screen out from under a call that is about to start, so we wait
     * for a real phase — or for this deadline — before believing IDLE.
     */
    private var seenLiveCall = false
    private val idleDeadline = SystemClock.elapsedRealtime() + START_GRACE_MS

    private val fadeTargets = HashMap<View, Boolean>()
    private val hideControls = Runnable { setControlsShown(false) }
    private val hideVolume = Runnable { fade(binding.volumePill, false) }
    private val rerender = Runnable { render(lastState) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyInsets()
        initRenderers()
        wireControls()
        setUpPip()
        installBackHandler()

        readIntent(intent)
        showPeer(intentName, intentCode)
        observeState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        if (autoAnswer) answer()
    }

    override fun onStart() {
        super.onStart()
        if (autoAnswer) answer()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        detachTracks()
        if (renderersReady) {
            binding.fullVideo.release()
            binding.pipVideo.release()
            renderersReady = false
        }
        super.onDestroy()
    }

    /** Volume keys drive Wisp's own gain, which can go above the system's 100%. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val service = CallService.instance
        val inCall = lastState.phase != CallPhase.IDLE && lastState.phase != CallPhase.INCOMING
        if (service != null && inCall) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    service.nudgeVolume(10)
                    showVolume()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    service.nudgeVolume(-10)
                    showVolume()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- setup -------------------------------------------------------------

    private fun readIntent(intent: Intent) {
        autoAnswer = intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)
        intent.getStringExtra(EXTRA_PEER_NAME)?.let { intentName = it }
        intent.getStringExtra(EXTRA_PEER_CODE)?.let { intentCode = it }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Keeps text and buttons clear of the notch, status bar and gesture bar. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.callRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            insetBottom = bars.bottom
            binding.header.updatePadding(top = bars.top + dp(8))
            binding.notices.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(64) }
            binding.reconnectBanner.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = bars.top + dp(120)
            }
            binding.pip.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(12) }
            binding.callControls.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bars.bottom + dp(16)
            }
            binding.incomingControls.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bars.bottom + dp(48)
            }
            binding.volumePill.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bars.bottom + dp(120)
            }
            insets
        }
    }

    private fun initRenderers() {
        val eglContext = RtcCore.eglBase(this).eglBaseContext
        binding.fullVideo.init(eglContext)
        binding.pipVideo.init(eglContext)
        binding.fullVideo.onFirstFrame = { onFirstFrame(fullTrack) }
        binding.pipVideo.onFirstFrame = { onFirstFrame(pipTrack) }
        // The pip's rounded background doubles as its clip, so the video inside
        // gets rounded corners too.
        binding.pip.clipToOutline = true
        renderersReady = true
    }

    private fun onFirstFrame(track: VideoTrack?) {
        if (track != null && track === watchedRemote) remoteFrameSeen = true
        render(lastState)
    }

    private fun wireControls() {
        binding.endCall.setOnClickListener { CallService.hangUp(this) }
        binding.decline.setOnClickListener { CallService.hangUp(this) }
        binding.answer.setOnClickListener { answer() }
        binding.minimize.setOnClickListener { finish() }

        binding.toggleMic.setOnClickListener {
            CallService.instance?.toggleMic()
            scheduleHide()
        }
        binding.toggleCamera.setOnClickListener {
            CallService.instance?.toggleCamera()
            scheduleHide()
        }
        binding.switchCamera.setOnClickListener {
            CallService.instance?.switchCamera()
            scheduleHide()
        }
        binding.toggleSpeaker.setOnClickListener {
            CallService.instance?.toggleSpeaker()
            scheduleHide()
        }
        binding.toggleSpeaker.setOnLongClickListener {
            showVolume()
            true
        }

        // Tapping the video shows or hides the controls, like any video app.
        binding.callRoot.setOnClickListener {
            if (isLive(lastState)) setControlsShown(!controlsShown)
        }

        // Hidden diagnostics: hold the name to see what the video is doing.
        binding.headerText.setOnLongClickListener {
            showStats()
            true
        }
        binding.noVideoChip.setOnClickListener { showStats() }

        binding.volumeSlider.max = 200
        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.volumeLabel.text = getString(R.string.volume_value, progress)
                if (fromUser) CallService.instance?.setVolumePercent(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                trackingVolume = true
                handler.removeCallbacks(hideVolume)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                trackingVolume = false
                handler.postDelayed(hideVolume, VOLUME_HIDE_MS)
            }
        })
    }

    /** Drag the self-view anywhere; let go and it settles into the nearest corner. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setUpPip() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false

        binding.pip.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = view.translationX
                    startY = view.translationY
                    dragging = false
                    view.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        view.translationX = startX + dx
                        view.translationY = startY + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) snapPip() else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapPip()
                    true
                }
                else -> false
            }
        }
        binding.pip.setOnClickListener {
            swapped = !swapped
            render(lastState)
            scheduleHide()
        }
    }

    private fun snapPip() {
        val pip = binding.pip
        val root = binding.callRoot
        val params = pip.layoutParams as FrameLayout.LayoutParams
        val leftmost = -(root.width - pip.width - 2 * params.marginEnd).toFloat()
        val bottomReserve = insetBottom + if (binding.callControls.visibility == View.VISIBLE) {
            binding.callControls.height + dp(32)
        } else {
            dp(24)
        }
        val lowest = (root.height - params.topMargin - pip.height - bottomReserve).toFloat().coerceAtLeast(0f)
        val centerX = pip.left + pip.translationX + pip.width / 2f
        val centerY = pip.top + pip.translationY + pip.height / 2f
        pip.animate()
            .translationX(if (centerX < root.width / 2f) leftmost else 0f)
            .translationY(if (centerY < root.height / 2f) 0f else lowest)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun answer() {
        autoAnswer = false
        CallService.answer(this)
    }

    // ---- state -------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallStateHolder.call.collect { render(it) }
            }
        }
    }

    private fun isLive(state: CallUi) =
        state.phase == CallPhase.ACTIVE || state.phase == CallPhase.RECONNECTING

    private fun render(state: CallUi) {
        lastState = state
        if (state.phase == CallPhase.IDLE) {
            val startingUp = !seenLiveCall &&
                state.endReason == null &&
                SystemClock.elapsedRealtime() < idleDeadline
            if (startingUp) {
                binding.peerCardStatus.text = getString(R.string.status_connecting)
                return
            }
            if (!finishing) {
                finishing = true
                // A toast outlives the activity; MainActivity repeats it if this
                // screen was never on top.
                state.endReason?.let {
                    Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show()
                    CallStateHolder.consumeEndReason()
                }
                detachTracks()
                finish()
            }
            return
        }
        seenLiveCall = true

        val live = isLive(state)
        val incoming = state.phase == CallPhase.INCOMING
        val name = state.peerName ?: intentName ?: ""
        showPeer(name, state.peerCode ?: intentCode)

        if (live && liveSinceMs == 0L) {
            liveSinceMs = SystemClock.elapsedRealtime()
            handler.postDelayed(rerender, NO_VIDEO_HINT_MS)
            scheduleHide()
        }
        if (!live && !controlsShown) setControlsShown(true)
        // You can't be in the big view with your camera off.
        if (!state.cameraOn) swapped = false

        // ---- which video goes where --------------------------------------
        val engine = CallStateHolder.engine
        val local = engine?.localVideoTrack?.takeIf { state.cameraOn }
        val remote = engine?.remoteVideoTrack
        if (remote !== watchedRemote) {
            watchedRemote = remote
            remoteFrameSeen = false
        }
        val (bigTrack, smallTrack) = when {
            !live -> local to null
            swapped -> local to remote
            else -> remote to local
        }
        route(bigTrack, smallTrack)
        binding.fullVideo.setMirror(bigTrack != null && bigTrack === local && state.frontCamera)
        binding.pipVideo.setMirror(smallTrack != null && smallTrack === local && state.frontCamera)

        // ---- big view: video, or their avatar card -----------------------
        val bigIsThem = live && !swapped
        val theirCameraOff = !state.remoteCameraOn
        binding.fullVideo.visibility =
            if (bigIsThem && theirCameraOff) View.INVISIBLE else View.VISIBLE
        fade(binding.peerCard, !live || (bigIsThem && theirCameraOff))
        fade(binding.dim, !live && bigTrack != null)
        binding.peerCardStatus.text = when (state.phase) {
            CallPhase.INCOMING -> getString(R.string.status_incoming_video)
            CallPhase.OUTGOING -> getString(R.string.status_calling)
            CallPhase.CONNECTING -> getString(R.string.status_connecting)
            else -> getString(R.string.peer_camera_off)
        }

        // ---- self-view ----------------------------------------------------
        val pipShown = live && smallTrack != null
        fade(binding.pip, pipShown)
        val pipShowsThemOff = swapped && theirCameraOff
        binding.pipAvatar.visibility = if (pipShowsThemOff) View.VISIBLE else View.GONE
        binding.pipVideo.visibility = if (pipShowsThemOff) View.INVISIBLE else View.VISIBLE

        // ---- header, notices ---------------------------------------------
        binding.headerText.visibility = if (live) View.VISIBLE else View.GONE
        binding.minimize.visibility = if (incoming) View.INVISIBLE else View.VISIBLE
        if (state.connectedAtMs > 0L) {
            binding.duration.base = state.connectedAtMs
            binding.duration.start()
            binding.duration.visibility = View.VISIBLE
        } else {
            binding.duration.stop()
            binding.duration.visibility = View.GONE
        }

        binding.mutedChip.text = getString(R.string.peer_muted, name)
        binding.mutedChip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_mic_off, 0, 0, 0)
        fade(binding.mutedChip, live && !state.remoteMicOn)

        val waitedLongEnough = liveSinceMs > 0L &&
            SystemClock.elapsedRealtime() - liveSinceMs >= NO_VIDEO_HINT_MS
        binding.noVideoChip.text = getString(R.string.no_video_yet, name)
        fade(binding.noVideoChip, live && state.remoteCameraOn && !remoteFrameSeen && waitedLongEnough)

        fade(binding.reconnectBanner, state.phase == CallPhase.RECONNECTING)

        // ---- controls -----------------------------------------------------
        val chrome = !live || controlsShown
        fade(binding.header, chrome)
        fade(binding.topScrim, chrome)
        fade(binding.bottomScrim, chrome)
        fade(binding.callControls, chrome && !incoming)
        fade(binding.incomingControls, incoming)

        styleToggle(
            binding.toggleMic,
            toggled = state.micMuted,
            icon = if (state.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on,
            description = if (state.micMuted) R.string.unmute else R.string.mute
        )
        styleToggle(
            binding.toggleCamera,
            toggled = !state.cameraOn,
            icon = if (state.cameraOn) R.drawable.ic_video_on else R.drawable.ic_video_off,
            description = if (state.cameraOn) R.string.camera_off_action else R.string.camera_on_action
        )
        styleToggle(
            binding.toggleSpeaker,
            toggled = state.speakerOn,
            icon = if (state.speakerOn) R.drawable.ic_speaker_on else R.drawable.ic_speaker_off,
            description = if (state.speakerOn) R.string.speaker_on else R.string.speaker_off
        )
        binding.switchCameraSlot.visibility = if (state.cameraOn) View.VISIBLE else View.GONE

        if (!trackingVolume && binding.volumeSlider.progress != state.volumePercent) {
            binding.volumeSlider.progress = state.volumePercent
        }
        binding.volumeLabel.text = getString(R.string.volume_value, state.volumePercent)
    }

    private fun showPeer(name: String?, code: String?) {
        val shown = name ?: ""
        binding.headerName.text = shown
        binding.peerCardName.text = shown
        Avatars.bind(binding.peerAvatar, shown, code)
        Avatars.bind(binding.pipAvatar, shown, code)
    }

    /** Toggled controls turn solid white with a dark icon, like Instagram's. */
    private fun styleToggle(button: ImageButton, toggled: Boolean, icon: Int, description: Int) {
        button.setBackgroundResource(if (toggled) R.drawable.bg_control_on else R.drawable.bg_control)
        button.setImageResource(icon)
        button.imageTintList = ColorStateList.valueOf(if (toggled) Color.BLACK else Color.WHITE)
        button.contentDescription = getString(description)
    }

    // ---- controls visibility ------------------------------------------------

    private fun setControlsShown(show: Boolean) {
        controlsShown = show
        handler.removeCallbacks(hideControls)
        render(lastState)
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (isLive(lastState)) handler.postDelayed(hideControls, CONTROLS_HIDE_MS)
    }

    private fun showVolume() {
        fade(binding.volumePill, true)
        handler.removeCallbacks(hideVolume)
        if (!trackingVolume) handler.postDelayed(hideVolume, VOLUME_HIDE_MS)
        scheduleHide()
    }

    /** Fades a view in or out; repeated calls with the same target are free. */
    private fun fade(view: View, visible: Boolean) {
        if (fadeTargets[view] == visible) return
        fadeTargets[view] = visible
        view.animate().cancel()
        if (visible) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        } else {
            view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                if (fadeTargets[view] == false) view.visibility = View.GONE
            }.start()
        }
    }

    // ---- tracks ------------------------------------------------------------

    private fun route(big: VideoTrack?, small: VideoTrack?) {
        if (!renderersReady) return
        if (fullTrack !== big) {
            fullTrack?.let { track -> runCatching { track.removeSink(binding.fullVideo) } }
            binding.fullVideo.reset()
            fullTrack = big
            big?.let { track -> runCatching { track.addSink(binding.fullVideo) } }
        }
        if (pipTrack !== small) {
            pipTrack?.let { track -> runCatching { track.removeSink(binding.pipVideo) } }
            binding.pipVideo.reset()
            pipTrack = small
            small?.let { track -> runCatching { track.addSink(binding.pipVideo) } }
        }
    }

    private fun detachTracks() {
        fullTrack?.let { track -> runCatching { track.removeSink(binding.fullVideo) } }
        pipTrack?.let { track -> runCatching { track.removeSink(binding.pipVideo) } }
        fullTrack = null
        pipTrack = null
    }

    // ---- diagnostics ---------------------------------------------------------

    private fun showStats() {
        val service = CallService.instance ?: return
        service.collectStats { text ->
            if (isFinishing || isDestroyed) return@collectStats
            AlertDialog.Builder(this)
                .setTitle(R.string.stats_title)
                .setMessage(text)
                .setPositiveButton(R.string.crash_copy) { _, _ -> copyToClipboard(text) }
                .setNeutralButton(R.string.refresh) { _, _ -> showStats() }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Wisp call details", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Backing out of a live call leaves it running in the service
                // rather than hanging up by accident; while ringing, back does
                // nothing at all so the call can't be dismissed by a stray swipe.
                if (CallStateHolder.call.value.phase == CallPhase.INCOMING) return
                finish()
            }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
