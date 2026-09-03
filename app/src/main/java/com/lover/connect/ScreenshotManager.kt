package com.lover.connect

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pixel-capture backend for Android 10 and older.
 *
 * A MediaProjection grant is deliberately kept only in memory. The user must
 * grant the system screen-capture consent again after the process or projection
 * session stops. One projection owns one persistent VirtualDisplay so the same
 * user-approved session can serve the periodic eyes timer safely.
 */
class ScreenshotManager(
    private val context: Context,
    private val onProjectionStopped: () -> Unit = {},
) {

    @Volatile
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var pendingCallback: ((String?) -> Unit)? = null
    private val captureInProgress = AtomicBoolean(false)

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    init {
        @Suppress("DEPRECATION")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    @Synchronized
    fun initProjection(resultCode: Int, data: Intent): Boolean {
        release()
        val captureThread = HandlerThread("LCScreenshot").apply { start() }
        val captureHandler = Handler(captureThread.looper)
        handlerThread = captureThread
        handler = captureHandler

        return try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data) ?: return failInitialization()
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    val unfinishedCallback = synchronized(this@ScreenshotManager) {
                        val pending = pendingCallback
                        pendingCallback = null
                        releaseCaptureSurface()
                        mediaProjection = null
                        projectionCallback = null
                        captureInProgress.set(false)
                        pending
                    }
                    unfinishedCallback?.invoke(null)
                    onProjectionStopped()
                }
            }
            projection.registerCallback(callback, captureHandler)
            projectionCallback = callback
            mediaProjection = projection

            val reader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                3,
            )
            val display = projection.createVirtualDisplay(
                "LCScreenshot",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            )

            imageReader = reader
            virtualDisplay = display
            true
        } catch (_: Exception) {
            release()
            false
        }
    }

    fun isReady(): Boolean = mediaProjection != null && virtualDisplay != null && imageReader != null

    fun takeScreenshot(callback: (String?) -> Unit) {
        val reader = imageReader
        val captureHandler = handler
        if (!isReady() || reader == null || captureHandler == null ||
            !captureInProgress.compareAndSet(false, true)
        ) {
            callback(null)
            return
        }

        synchronized(this) {
            pendingCallback = callback
        }

        reader.setOnImageAvailableListener({ source ->
            consumePendingImage(source)
        }, captureHandler)

        // A persistent VirtualDisplay may already have filled ImageReader's
        // queue before a capture is requested. Some Android 8-10 devices do
        // not dispatch an availability callback for those existing frames;
        // new frames are then dropped because the queue is full. Consume the
        // newest queued frame immediately on the reader thread, while keeping
        // the listener installed as a fallback when no frame exists yet.
        captureHandler.post {
            consumePendingImage(reader)
        }

        captureHandler.postDelayed({
            val timedOutCallback = synchronized(this) {
                if (pendingCallback === callback) {
                    pendingCallback.also { pendingCallback = null }
                } else {
                    null
                }
            }
            if (timedOutCallback != null) {
                reader.setOnImageAvailableListener(null, null)
                captureInProgress.set(false)
                timedOutCallback(null)
            }
        }, 3_000)
    }

    private fun consumePendingImage(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        val activeCallback = synchronized(this) {
            pendingCallback.also { pendingCallback = null }
        }
        if (activeCallback == null) {
            image.close()
            return
        }

        source.setOnImageAvailableListener(null, null)
        val base64 = try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            if (cropped !== bitmap) bitmap.recycle()
            try {
                bitmapToBase64(cropped)
            } finally {
                cropped.recycle()
            }
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }

        captureInProgress.set(false)
        activeCallback(base64)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    @Synchronized
    private fun failInitialization(): Boolean {
        releaseCaptureSurface()
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        captureInProgress.set(false)
        return false
    }

    @Synchronized
    private fun releaseCaptureSurface() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    @Synchronized
    fun release() {
        val unfinishedCallback = pendingCallback
        pendingCallback = null
        releaseCaptureSurface()
        val projection = mediaProjection
        val callback = projectionCallback
        mediaProjection = null
        projectionCallback = null
        if (projection != null && callback != null) {
            try {
                projection.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        captureInProgress.set(false)
        unfinishedCallback?.invoke(null)
    }
}
