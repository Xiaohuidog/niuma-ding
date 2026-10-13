package com.niuma.checkin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.niuma.core.VisualBox
import com.niuma.core.VisualText
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

/** Local-only screenshot OCR shared by the punch and Statistics steps. */
class DingTalkVisualReader(
    private val app: CheckinApp,
    private val navigator: DingTalkWorkbenchNavigator
) : Closeable {
    data class Screen(val bitmap: Bitmap, val text: List<VisualText>) : Closeable {
        override fun close() { bitmap.recycle() }
    }

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    var lastFailure: String? = null
        private set

    fun capture(allowSystemOverlay: Boolean = false): Screen? {
        if (!navigator.isDingTalkVisible() &&
            !(allowSystemOverlay && navigator.isSystemOverlayVisible())) {
            lastFailure = "钉钉页面不可见"
            return null
        }
        val evidence = app.screenCapture.capture("dingtalk-read-${System.nanoTime()}")
        if (!evidence.ready) {
            lastFailure = evidence.reason
            return null
        }
        val file = File(evidence.path)
        val bitmap = try { BitmapFactory.decodeFile(file.absolutePath) } finally { file.delete() }
            ?: run { lastFailure = "截图解码失败"; return null }
        return try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 5, TimeUnit.SECONDS)
            val lines = result.textBlocks.flatMap { block -> block.lines.mapNotNull { line ->
                line.boundingBox?.let { box ->
                    VisualText(line.text, VisualBox(box.left, box.top, box.right, box.bottom))
                }
            } }
            lastFailure = null
            Screen(bitmap, lines)
        } catch (error: Exception) {
            lastFailure = "页面文字识别失败：${error.javaClass.simpleName}"
            bitmap.recycle()
            null
        }
    }

    override fun close() { recognizer.close() }
}
