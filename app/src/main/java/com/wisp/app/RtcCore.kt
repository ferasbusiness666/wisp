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
 */
object RtcCore {

    val eglBase: EglBase by lazy { EglBase.create() }

    @Volatile
    private var factoryInstance: PeerConnectionFactory? = null

    fun factory(context: Context): PeerConnectionFactory {
        factoryInstance?.let { return it }
        synchronized(this) {
            factoryInstance?.let { return it }
            val app = context.applicationContext

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )

            val audioDeviceModule = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            // Hardware encoders keep the radio and the CPU cool; falling back to
            // software only happens when the device has no HW codec for the
            // negotiated profile.
            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase.eglBaseContext,
                /* enableIntelVp8Encoder = */ true,
                /* enableH264HighProfile = */ true
            )
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

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
