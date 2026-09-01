package com.wisp.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wisp.app.databinding.ActivityCallBinding
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

/** Full-screen call UI. All state comes from the service via [CallStateHolder]. */
class CallActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PEER_CODE = "peer_code"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_AUTO_ANSWER = "auto_answer"

        /** How long to wait for the service to report a phase before giving up. */
        private const val START_GRACE_MS = 12_000L

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

    private var renderersReady = false
    private var attachedRemote: VideoTrack? = null
    private var attachedLocal: VideoTrack? = null
    private var autoAnswer = false
    private var finishing = false

    /**
     * The service starts asynchronously, so for the first moments after we are
     * launched the shared state can still read IDLE. Finishing on that would
     * close the screen out from under a call that is about to start, so we wait
     * for a real phase — or for this deadline — before believing IDLE.
     */
    private var seenLiveCall = false
    private val idleDeadline = SystemClock.elapsedRealtime() + START_GRACE_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initRenderers()
        wireControls()
        installBackHandler()

        autoAnswer = intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)
        intent.getStringExtra(EXTRA_PEER_NAME)?.let { binding.peerName.text = it }

        observeState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoAnswer = intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)
        intent.getStringExtra(EXTRA_PEER_NAME)?.let { binding.peerName.text = it }
        if (autoAnswer) answer()
    }

    override fun onStart() {
        super.onStart()
        if (autoAnswer) answer()
    }

    override fun onDestroy() {
        detachTracks()
        if (renderersReady) {
            binding.remoteView.release()
            binding.localView.release()
            renderersReady = false
        }
        super.onDestroy()
    }

    // ---- setup -------------------------------------------------------------

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

    private fun initRenderers() {
        val eglContext = RtcCore.eglBase(this).eglBaseContext
        binding.remoteView.init(eglContext, null)
        binding.remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        binding.remoteView.setEnableHardwareScaler(true)

        binding.localView.init(eglContext, null)
        binding.localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        binding.localView.setEnableHardwareScaler(true)
        binding.localView.setMirror(true)
        binding.localView.setZOrderMediaOverlay(true)
        renderersReady = true
    }

    private fun wireControls() {
        binding.endCall.setOnClickListener { CallService.hangUp(this) }
        binding.decline.setOnClickListener { CallService.hangUp(this) }
        binding.answer.setOnClickListener { answer() }
        binding.toggleMic.setOnClickListener { CallService.instance?.toggleMic() }
        binding.toggleCamera.setOnClickListener { CallService.instance?.toggleCamera() }
        binding.switchCamera.setOnClickListener { CallService.instance?.switchCamera() }

        binding.volumeSlider.max = 200
        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.volumeLabel.text = getString(R.string.volume_percent, progress)
                if (fromUser) CallService.instance?.setVolumePercent(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
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

    private fun render(state: CallUi) {
        if (state.phase == CallPhase.IDLE) {
            val startingUp = !seenLiveCall &&
                state.endReason == null &&
                SystemClock.elapsedRealtime() < idleDeadline
            if (startingUp) {
                binding.status.text = getString(R.string.status_connecting)
                return
            }
            if (!finishing) {
                finishing = true
                // Setting a label on a screen that is about to close shows the
                // user nothing; a toast outlives the activity, and MainActivity
                // repeats it if this screen was never on top.
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

        state.peerName?.let { binding.peerName.text = it }

        val incoming = state.phase == CallPhase.INCOMING
        binding.incomingControls.visibility = if (incoming) View.VISIBLE else View.GONE
        binding.callControls.visibility = if (incoming) View.GONE else View.VISIBLE

        binding.status.text = when (state.phase) {
            CallPhase.INCOMING -> getString(R.string.status_incoming)
            CallPhase.OUTGOING -> getString(R.string.status_calling)
            CallPhase.CONNECTING -> getString(R.string.status_connecting)
            CallPhase.RECONNECTING -> getString(R.string.status_reconnecting)
            else -> ""
        }
        binding.status.visibility =
            if (state.phase == CallPhase.ACTIVE) View.GONE else View.VISIBLE

        // DISCONNECTED is transient: show the overlay, keep the call alive and
        // let the service's grace timer decide when to give up.
        binding.reconnectingOverlay.visibility =
            if (state.phase == CallPhase.RECONNECTING) View.VISIBLE else View.GONE

        if (state.connectedAtMs > 0L) {
            binding.duration.visibility = View.VISIBLE
            binding.duration.base = state.connectedAtMs
            binding.duration.start()
        } else {
            binding.duration.stop()
            binding.duration.visibility = View.GONE
        }

        binding.toggleMic.setImageResource(
            if (state.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on
        )
        binding.toggleMic.contentDescription =
            getString(if (state.micMuted) R.string.unmute else R.string.mute)
        binding.toggleCamera.setImageResource(
            if (state.cameraOn) R.drawable.ic_video_on else R.drawable.ic_video_off
        )
        binding.localView.visibility = if (state.cameraOn) View.VISIBLE else View.INVISIBLE

        if (binding.volumeSlider.progress != state.volumePercent) {
            binding.volumeSlider.progress = state.volumePercent
        }
        binding.volumeLabel.text = getString(R.string.volume_percent, state.volumePercent)

        attachTracks()
    }

    private fun attachTracks() {
        val engine = CallStateHolder.engine ?: return
        if (!renderersReady) return

        val remote = engine.remoteVideoTrack
        if (remote !== attachedRemote) {
            attachedRemote?.let { runCatching { it.removeSink(binding.remoteView) } }
            attachedRemote = remote
            remote?.let { runCatching { it.addSink(binding.remoteView) } }
            binding.remotePlaceholder.visibility = if (remote == null) View.VISIBLE else View.GONE
        }

        val local = engine.localVideoTrack
        if (local !== attachedLocal) {
            attachedLocal?.let { runCatching { it.removeSink(binding.localView) } }
            attachedLocal = local
            local?.let { runCatching { it.addSink(binding.localView) } }
        }
    }

    private fun detachTracks() {
        attachedRemote?.let { runCatching { it.removeSink(binding.remoteView) } }
        attachedLocal?.let { runCatching { it.removeSink(binding.localView) } }
        attachedRemote = null
        attachedLocal = null
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
}
