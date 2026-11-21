package com.stevencastro.wornrun

import android.content.Context
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records the glasses' outward camera directly, without opening the vendor camera UI. */
@Suppress("DEPRECATION")
class GlassesVideoRecorder(context: Context) {
    private val appContext = context.applicationContext
    private var camera: Camera? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start() {
        if (isRecording) return
        try {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "WornRun",
            )
            check(directory.exists() || directory.mkdirs()) { "Couldn't create the video folder" }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(directory, "WornRun_$timestamp.mp4")
            outputFile = file

            val activeCamera = Camera.open(0)
            camera = activeCamera
            val parameters = activeCamera.parameters
            parameters.setRecordingHint(true)
            val availableSizes = parameters.supportedVideoSizes ?: parameters.supportedPreviewSizes
            val profile = runCatching { CamcorderProfile.get(0, CamcorderProfile.QUALITY_720P) }.getOrNull()
            val size = availableSizes?.firstOrNull { it.width == profile?.videoFrameWidth && it.height == profile.videoFrameHeight }
                ?: availableSizes?.filter { it.width <= 1920 && it.height <= 1080 }?.maxByOrNull { it.width * it.height }
                ?: availableSizes?.firstOrNull()
            check(size != null) { "The glasses camera has no supported video size" }
            activeCamera.parameters = parameters
            activeCamera.unlock()

            val mediaRecorder = MediaRecorder()
            recorder = mediaRecorder
            mediaRecorder.setCamera(activeCamera)
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(size.width, size.height)
            mediaRecorder.setVideoFrameRate(profile?.videoFrameRate?.coerceAtMost(30) ?: 24)
            mediaRecorder.setVideoEncodingBitRate(profile?.videoBitRate ?: (size.width * size.height * 4))
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.setOrientationHint(0)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (error: Exception) {
            release(deleteIncomplete = true)
            throw IllegalStateException(error.message ?: "Couldn't start video recording", error)
        }
    }

    fun stop() {
        val file = outputFile
        val mediaRecorder = recorder ?: return
        var saved = false
        try {
            mediaRecorder.stop()
            saved = true
        } catch (_: RuntimeException) {
            // MediaRecorder may throw if stopped before it receives its first frame.
        } finally {
            release(deleteIncomplete = !saved)
        }
        if (saved && file != null) {
            MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        }
    }

    private fun release(deleteIncomplete: Boolean) {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { camera?.lock() }
        runCatching { camera?.release() }
        camera = null
        if (deleteIncomplete) outputFile?.delete()
        outputFile = null
    }
}
