package com.niuma.checkin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.niuma.core.screenshotEvidenceFileName
import java.io.File
import java.io.FileOutputStream

data class ScreenshotEvidence(val ready: Boolean, val path: String = "", val reason: String = "")

class ScreenCaptureSession(private val context: Context) {
    companion object {
        const val REQUEST_CODE = 4103
        const val EXTRA_RESULT_CODE = "captureResultCode"
        const val EXTRA_DATA = "captureData"
    }
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var stopped = false

    val authorized: Boolean get() = synchronized(lock) { projection != null && !stopped }

    fun requestPermission(activity: Activity) {
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    fun acceptPermission(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) return false
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        synchronized(lock) {
            releaseLocked()
            projection = manager.getMediaProjection(resultCode, data)
            stopped = false
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { synchronized(lock) { releaseLocked() } }
            }, handler)
            createDisplayLocked()
        }
        return authorized
    }

    fun capture(runId: String, timeoutMs: Long = 3_000L): ScreenshotEvidence {
        val outputDir = File(context.filesDir, "screenshot-evidence").apply { mkdirs() }
        val output = File(outputDir, screenshotEvidenceFileName(runId))
        if (!authorized) return ScreenshotEvidence(false, reason = "未授权截图或截图会话已失效")
        val imageReader = synchronized(lock) { reader }
            ?: return ScreenshotEvidence(false, reason = "截图显示会话未建立")
        val deadline = System.currentTimeMillis() + timeoutMs
        return try {
            var image: android.media.Image? = null
            while (image == null && System.currentTimeMillis() < deadline) {
                image = imageReader.acquireLatestImage()
                if (image == null) Thread.sleep(40)
            }
            if (image == null) return ScreenshotEvidence(false, reason = "截图帧获取超时")
            image.use {
                val plane = it.planes[0]
                val width = it.width
                val height = it.height
                val rowPadding = plane.rowStride - plane.pixelStride * width
                val raw = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(raw, 0, 0, width, height)
                raw.recycle()
                FileOutputStream(output).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                bitmap.recycle()
            }
            ScreenshotEvidence(true, output.absolutePath)
        } catch (error: Exception) {
            output.delete()
            ScreenshotEvidence(false, reason = "截图写入失败：${error.javaClass.simpleName}")
        }
    }

    /** Keep the already verified DingTalk screen for the notification email. */
    fun saveEvidence(runId: String, bitmap: Bitmap): ScreenshotEvidence {
        val outputDir = File(context.filesDir, "screenshot-evidence").apply { mkdirs() }
        val output = File(outputDir, screenshotEvidenceFileName(runId))
        return try {
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG 编码失败" }
            }
            ScreenshotEvidence(true, output.absolutePath)
        } catch (error: Exception) {
            output.delete()
            ScreenshotEvidence(false, reason = "截图写入失败：${error.javaClass.simpleName}")
        }
    }

    private fun releaseLocked() {
        if (stopped && projection == null) return
        stopped = true
        display?.release(); display = null
        reader?.close(); reader = null
        val active = projection
        projection = null
        active?.stop()
    }

    private fun createDisplayLocked() {
        val active = projection ?: return
        // Accessibility gestures use physical display coordinates. App displayMetrics excludes
        // the status/navigation inset on this device, which shifted taps above the result link.
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels,
            android.graphics.PixelFormat.RGBA_8888, 3)
        reader = imageReader
        display = active.createVirtualDisplay(
            "niuma-checkin-screenshot", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler
        )
    }
}
