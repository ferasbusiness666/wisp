package com.wisp.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws a WebRTC video track into a TextureView.
 *
 * WebRTC's own SurfaceViewRenderer punches a hole through the window, which is
 * why its background used to cover the self-view and why it can't be rounded,
 * dragged or swapped with the big video. A TextureView is an ordinary view, so
 * all of that just works. Frames are cropped to fill the view.
 */
class VideoTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener, VideoSink {

    private val renderer = EglRenderer("VideoTextureView@${System.identityHashCode(this)}")
    private val main = Handler(Looper.getMainLooper())

    private var initialized = false

    @Volatile
    private var sawFrame = false

    /** Called once on the main thread when the first frame of a track arrives. */
    var onFirstFrame: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun init(sharedContext: EglBase.Context) {
        if (initialized) return
        renderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        initialized = true
        surfaceTexture?.let { renderer.createEglSurface(it) }
        updateAspect(width, height)
    }

    fun setMirror(mirror: Boolean) = renderer.setMirror(mirror)

    /** Blanks the view and re-arms [onFirstFrame], e.g. when the track changes. */
    fun reset() {
        sawFrame = false
        if (initialized) renderer.clearImage()
    }

    fun hasFrames(): Boolean = sawFrame

    fun release() {
        if (!initialized) return
        initialized = false
        renderer.release()
    }

    override fun onFrame(frame: VideoFrame) {
        if (!sawFrame) {
            sawFrame = true
            main.post { onFirstFrame?.invoke() }
        }
        renderer.onFrame(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateAspect(w, h)
    }

    private fun updateAspect(w: Int, h: Int) {
        if (w > 0 && h > 0) renderer.setLayoutAspectRatio(w / h.toFloat())
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized) renderer.createEglSurface(surface)
        updateAspect(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateAspect(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // The GL thread must let go of the surface before the system frees it.
        val done = CountDownLatch(1)
        renderer.releaseEglSurface { done.countDown() }
        runCatching { done.await(1, TimeUnit.SECONDS) }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
