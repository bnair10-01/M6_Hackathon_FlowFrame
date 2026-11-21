package com.stevencastro.wornhub

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * The glasses' camera as a stream of JPEG frames, running only while someone
 * watches. Viewers call [acquire]/[release] and block on [nextFrame]. The
 * camera is released when the last viewer leaves, so the glasses' own camera
 * app keeps working.
 */
class LiveCamera(private val owner: LifecycleOwner, private val context: android.content.Context) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Object()
    private var viewers = 0
    private var frame: ByteArray? = null
    private var frameSeq = 0L
    private var lastFrameMs = 0L
    private var provider: ProcessCameraProvider? = null

    val viewerCount: Int get() = synchronized(lock) { viewers }

    fun acquire() {
        val first = synchronized(lock) { ++viewers == 1 }
        if (first) main.post(::start)
    }

    fun release() {
        val last = synchronized(lock) { --viewers == 0 }
        if (last) main.post(::stop)
    }

    /** Waits for a frame newer than [afterSeq]; returns (seq, jpeg) or null on timeout. */
    fun nextFrame(afterSeq: Long, timeoutMs: Long = 5000): Pair<Long, ByteArray>? = synchronized(lock) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (frameSeq <= afterSeq) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            lock.wait(left)
        }
        frameSeq to frame!!
    }

    private fun start() {
        // The M6 has one back camera but also advertises a front one, which
        // fails CameraX's startup check. Same workaround as Worn Link.
        try {
            ProcessCameraProvider.configureInstance(CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA).build())
        } catch (alreadyConfigured: IllegalStateException) {
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                if (viewerCount == 0) return@addListener
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, ::onFrame)
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                Log.i(TAG, "camera on")
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
            }
        }, context.mainExecutor)
    }

    private fun stop() {
        if (viewerCount > 0) return
        provider?.unbindAll()
        Log.i(TAG, "camera off")
    }

    private fun onFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMs < FRAME_INTERVAL_MS) return
            lastFrameMs = now
            val jpeg = toJpeg(image)
            synchronized(lock) {
                frame = jpeg
                frameSeq++
                lock.notifyAll()
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame failed", e)
        } finally {
            image.close()
        }
    }

    companion object {
        private const val TAG = "WornHub"
        private const val FRAME_INTERVAL_MS = 80L // ~12 fps
        private const val JPEG_QUALITY = 70

        /** YUV_420_888 to JPEG via NV21 (handles any row and pixel stride). From Worn Link. */
        fun toJpeg(image: ImageProxy): ByteArray {
            val width = image.width
            val height = image.height
            val nv21 = ByteArray(width * height * 3 / 2)
            val y = image.planes[0]
            val yBuffer: ByteBuffer = y.buffer
            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * y.rowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
            val u = image.planes[1]
            val v = image.planes[2]
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val i = row * u.rowStride + col * u.pixelStride
                    nv21[pos++] = v.buffer.get(i)
                    nv21[pos++] = u.buffer.get(i)
                }
            }
            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
            return out.toByteArray()
        }
    }
}
