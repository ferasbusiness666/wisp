package com.wisp.app

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Process-wide WebRTC singletons.
 *
 * The factory and the EGL context are expensive and must be shared between the
 * service (which owns the call) and the activity (which owns the renderers),
 * so they live here rather than inside either one.
 *
 * Every entry point loads the native library first. Several WebRTC classes call
 * straight into native code from their constructors, so touching any of them
 * beforehand is a native crash — one that never surfaces as a Java exception.
 */
object RtcCore {

    @Volatile
    private var nativeReady = false

    @Volatile
    private var eglBaseInstance: EglBase? = null

    @Volatile
    private var factoryInstance: PeerConnectionFactory? = null

    /** Loads libwebrtc. Safe to call repeatedly; only the first call does work. */
    fun ensureNative(context: Context) {
        if (nativeReady) return
        synchronized(this) {
            if (nativeReady) return
            CrashLog.step(context, "video: loading library")
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            nativeReady = true
        }
    }

    /**
     * Shared EGL context for capture, encoding and the on-screen renderers.
     * Takes a context so the native library is guaranteed loaded first.
     */
    fun eglBase(context: Context): EglBase {
        eglBaseInstance?.let { return it }
        synchronized(this) {
            eglBaseInstance?.let { return it }
            ensureNative(context)
            CrashLog.step(context, "video: creating graphics context")
            return EglBase.create().also { eglBaseInstance = it }
        }
    }

    fun factory(context: Context): PeerConnectionFactory {
        factoryInstance?.let { return it }
        synchronized(this) {
            factoryInstance?.let { return it }
            val app = context.applicationContext
            ensureNative(app)

            CrashLog.step(app, "video: opening audio device")
            val audioDeviceModule = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            val eglContext = eglBase(app).eglBaseContext

            // Hardware encoders keep the radio and the CPU cool; falling back to
            // software only happens when the device has no HW codec for the
            // negotiated profile.
            CrashLog.step(app, "video: building codecs")
            val encoderFactory = DefaultVideoEncoderFactory(
                eglContext,
                /* enableIntelVp8Encoder = */ true,
                /* enableH264HighProfile = */ true
            )
            val decoderFactory = DefaultVideoDecoderFactory(eglContext)

            CrashLog.step(app, "video: building factory")
            val created = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            factoryInstance = created
            return created
        }
    }
}
