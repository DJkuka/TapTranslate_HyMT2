package com.tap_to_translate.snap_translate.hymt2

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import com.tap_to_translate.snap_translate.hymt2.ocr.BubblePopSegmenter
import com.paddle.ocr.util.OpenCVUtils
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/** Standalone BubblePop contour diagnostic. It does not modify the live OCR/translation pipeline. */
class BubblePopDiagnosticActivity : Activity() {
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private var source: Bitmap? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "BubblePop · диагностика контуров"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "Проверка формы баблов"
            textSize = 21f
        })
        root.addView(TextView(this).apply {
            text = "Выбери страницу комикса. Модель выделит маски речевых баблов и покажет их контуры. Результат используется только для диагностики и не меняет перевод."
            textSize = 14f
        })
        root.addView(Button(this).apply {
            text = "Выбрать изображение"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, REQUEST_IMAGE)
            }
        })
        root.addView(Button(this).apply {
            text = "Найти контуры баблов"
            setOnClickListener { runSegmentation() }
        })
        status = TextView(this).apply { text = "Изображение не выбрано."; textSize = 13f }
        root.addView(status)
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    @Deprecated("Legacy document picker for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openInputStream(uri).use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (opts.outWidth / sample > MAX_SIDE || opts.outHeight / sample > MAX_SIDE) sample *= 2
                val bitmap = BitmapFactory.Options().let { o ->
                    o.inSampleSize = sample
                    contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
                } ?: error("Не удалось прочитать изображение")
                source?.recycle()
                source = bitmap
                preview.setImageBitmap(bitmap)
                status.text = "Изображение: ${bitmap.width} × ${bitmap.height}. Готово к анализу."
            }
        } catch (t: Throwable) {
            status.text = "Ошибка чтения изображения: ${t.message}"
        }
    }

    private fun runSegmentation() {
        val original = source ?: run {
            Toast.makeText(this, "Сначала выбери изображение", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Загрузка BubblePop и поиск контуров…"
        thread(name = "BubblePopDiagnostic") {
            var segmenter: BubblePopSegmenter? = null
            var mat: Mat? = null
            var mask: Mat? = null
            val contours = ArrayList<MatOfPoint>()
            try {
                val model = File(cacheDir, "bubblepop_model_fp16.onnx")
                if (!model.exists() || model.length() < 100_000) {
                    assets.open("models/bubblepop/model_fp16.onnx").use { input ->
                        FileOutputStream(model).use { output -> input.copyTo(output) }
                    }
                }
                check(OpenCVUtils.init(applicationContext)) { "OpenCV initialization failed" }
                segmenter = BubblePopSegmenter(model.absolutePath)
                mat = Mat()
                Utils.bitmapToMat(original, mat)
                mask = segmenter.getMask(mat, null)
                val hierarchy = Mat()
                Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                hierarchy.release()
                val overlay = original.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(overlay)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(2f, original.width / 500f)
                    color = 0xFFFF3B30.toInt()
                }
                var totalPoints = 0
                for (contour in contours) {
                    val pts = contour.toArray()
                    if (pts.size < 3) continue
                    totalPoints += pts.size
                    val path = Path().apply {
                        moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
                        for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
                main.post {
                    preview.setImageBitmap(overlay)
                    status.text = "Найдено контуров: ${contours.size}. Всего вершин: $totalPoints. Красные линии показывают внешние границы масок. Время включает запуск модели."
                }
                hierarchyRelease(contours)
            } catch (t: Throwable) {
                main.post { status.text = "Ошибка BubblePop: ${t.javaClass.simpleName}: ${t.message}" }
            } finally {
                contours.forEach { it.release() }
                mask?.release()
                mat?.release()
                segmenter?.close()
            }
        }
    }

    private fun hierarchyRelease(items: List<MatOfPoint>) = Unit

    override fun onDestroy() {
        source?.recycle()
        source = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_IMAGE = 7401
        private const val MAX_SIDE = 1600
    }
}
