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
import android.os.SystemClock
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
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import com.tap_to_translate.snap_translate.hymt2.ocr.BubblePopSegmenter
import com.tap_to_translate.snap_translate.hymt2.ocr.BubblePopWatershedSplitter
import com.tap_to_translate.snap_translate.hymt2.ocr.BubblePopBoundarySplitter
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
        root.addView(Button(this).apply {
            text = "Показать карту границ"
            setOnClickListener { runBoundaryDiagnostic() }
        })
        root.addView(Button(this).apply {
            text = "Разделить по карте границ (новый метод)"
            setOnClickListener { runBoundarySplitDiagnostic() }
        })
        root.addView(Button(this).apply {
            text = "Разделить по геометрии маски + boundary"
            setOnClickListener { runWatershedDiagnostic() }
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
        val inputBitmap = try {
            original.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            status.text = "Не удалось подготовить копию изображения: ${t.message}"
            return
        } ?: run {
            status.text = "Не удалось создать копию изображения."
            return
        }
        status.text = "Загрузка BubblePop и поиск контуров…"
        thread(name = "BubblePopDiagnostic") {
            val startedAt = SystemClock.elapsedRealtime()
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
                Utils.bitmapToMat(inputBitmap, mat)
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
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val contourCount = contours.size
                main.post {
                    if (!isFinishing) {
                        preview.setImageBitmap(overlay)
                        status.text = "Найдено контуров: $contourCount. Вершин: $totalPoints. Время: ${elapsedMs} мс (включая загрузку модели). Красные линии показывают внешние границы масок."
                    } else {
                        overlay.recycle()
                    }
                }
            } catch (t: Throwable) {
                main.post { status.text = "Ошибка BubblePop: ${t.javaClass.simpleName}: ${t.message}" }
            } finally {
                contours.forEach { it.release() }
                mask?.release()
                mat?.release()
                segmenter?.close()
                if (!inputBitmap.isRecycled) inputBitmap.recycle()
            }
        }
    }

    
    /** Displays the raw boundary-head probability map without changing segmentation. */
    private fun runBoundaryDiagnostic() {
        val original = source ?: run {
            Toast.makeText(this, "Сначала выбери изображение", Toast.LENGTH_SHORT).show()
            return
        }
        val inputBitmap = try {
            original.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            status.text = "Не удалось подготовить копию изображения: ${t.message}"
            return
        } ?: run {
            status.text = "Не удалось создать копию изображения."
            return
        }
        status.text = "Построение карты границ BubblePop…"
        thread(name = "BubblePopBoundaryDiagnostic") {
            val startedAt = SystemClock.elapsedRealtime()
            var segmenter: BubblePopSegmenter? = null
            var mat: Mat? = null
            var boundary: Mat? = null
            var rgba: Mat? = null
            var overlay: Bitmap? = null
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
                Utils.bitmapToMat(inputBitmap, mat)
                boundary = segmenter.getBoundaryMap(mat)
                rgba = Mat()
                Imgproc.cvtColor(boundary, rgba, Imgproc.COLOR_GRAY2RGBA)
                overlay = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rgba, overlay!!)
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val result = overlay
                main.post {
                    if (!isFinishing && result != null) {
                        preview.setImageBitmap(result)
                        status.text = "Карта границ модели. Белые участки показывают уверенность boundary-канала; это ещё не разделённые баблы. Время: ${elapsedMs} мс."
                    } else {
                        result?.recycle()
                    }
                }
            } catch (t: Throwable) {
                overlay?.recycle()
                main.post { status.text = "Ошибка карты границ: ${t.javaClass.simpleName}: ${t.message}" }
            } finally {
                boundary?.release()
                rgba?.release()
                mat?.release()
                segmenter?.close()
                if (!inputBitmap.isRecycled) inputBitmap.recycle()
            }
        }
    }


    /** Splits merged mask components using only BubblePop's boundary-head predictions. */
    private fun runBoundarySplitDiagnostic() {
        val original = source ?: run {
            Toast.makeText(this, "Сначала выбери изображение", Toast.LENGTH_SHORT).show()
            return
        }
        val inputBitmap = try {
            original.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            status.text = "Не удалось подготовить копию изображения: ${t.message}"
            return
        } ?: run {
            status.text = "Не удалось создать копию изображения."
            return
        }
        status.text = "Разделение по карте границ BubblePop…"
        thread(name = "BubblePopBoundarySplitDiagnostic") {
            val startedAt = SystemClock.elapsedRealtime()
            var segmenter: BubblePopSegmenter? = null
            var mat: Mat? = null
            var mask: Mat? = null
            var boundary: Mat? = null
            var result: BubblePopBoundarySplitter.Result? = null
            var overlay: Bitmap? = null
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
                Utils.bitmapToMat(inputBitmap, mat)
                mask = segmenter.getMask(mat, null)
                boundary = segmenter.getBoundaryMap(mat)
                result = BubblePopBoundarySplitter.split(mask, boundary)

                val instanceMask = Mat()
                val hierarchy = Mat()
                try {
                    for (instanceId in 1..result!!.instanceCount) {
                        Core.compare(result!!.instances, Scalar(instanceId.toDouble()),
                            instanceMask, Core.CMP_EQ)
                        val localContours = ArrayList<MatOfPoint>()
                        Imgproc.findContours(instanceMask, localContours, hierarchy,
                            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        contours.addAll(localContours)
                    }
                } finally {
                    instanceMask.release()
                    hierarchy.release()
                }

                val resultBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(resultBitmap)
                val palette = intArrayOf(
                    0xFFFF453A.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt(),
                    0xFFFF9F0A.toInt(), 0xFFBF5AF2.toInt(), 0xFF64D2FF.toInt(),
                    0xFFFF375F.toInt(), 0xFFAC8E68.toInt()
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(2f, original.width / 500f)
                }
                var points = 0
                for ((index, contour) in contours.withIndex()) {
                    val pts = contour.toArray()
                    if (pts.size < 3) continue
                    points += pts.size
                    val path = Path().apply {
                        moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
                        for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
                        close()
                    }
                    paint.color = palette[index % palette.size]
                    canvas.drawPath(path, paint)
                }
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val finalBitmap = resultBitmap
                val inputCount = result!!.inputComponents
                val outputCount = result!!.outputComponents
                val splitCount = result!!.splitComponents
                val threshold = result!!.boundaryThreshold
                val contourCount = contours.size
                contours.forEach { it.release() }
                contours.clear()
                main.post {
                    if (!isFinishing) {
                        preview.setImageBitmap(finalBitmap)
                        status.text = "Boundary-only: исходных областей $inputCount, итоговых областей $outputCount, разделено: $splitCount. Порог boundary: ${if (threshold < 0) "не применялся" else threshold}. Контуров: $contourCount. Вершин: $points. Время: ${elapsedMs} мс."
                    } else {
                        finalBitmap.recycle()
                    }
                }
            } catch (t: Throwable) {
                overlay?.recycle()
                main.post { status.text = "Ошибка разделения по карте границ: ${t.javaClass.simpleName}: ${t.message}" }
            } finally {
                contours.forEach { it.release() }
                result?.instances?.release()
                result?.mask?.release()
                boundary?.release()
                mask?.release()
                mat?.release()
                segmenter?.close()
                if (!inputBitmap.isRecycled) inputBitmap.recycle()
            }
        }
    }

    /** Experimental watershed comparison. Original mask remains unchanged. */
    private fun runWatershedDiagnostic() {
        val original = source ?: run {
            Toast.makeText(this, "Сначала выбери изображение", Toast.LENGTH_SHORT).show()
            return
        }
        val inputBitmap = try {
            original.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            status.text = "Не удалось подготовить копию изображения: ${t.message}"
            return
        } ?: run {
            status.text = "Не удалось создать копию изображения."
            return
        }
        status.text = "Геометрическое разделение смежных баблов (watershed)…"
        thread(name = "BubblePopWatershedDiagnostic") {
            val startedAt = SystemClock.elapsedRealtime()
            var segmenter: BubblePopSegmenter? = null
            var mat: Mat? = null
            var mask: Mat? = null
            var boundary: Mat? = null
            var splitMask: Mat? = null
            var instanceMap: Mat? = null
            var overlay: Bitmap? = null
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
                Utils.bitmapToMat(inputBitmap, mat)
                // Two model passes for now: preserve the baseline API and compare raw outputs.
                mask = segmenter.getMask(mat, null)
                boundary = segmenter.getBoundaryMap(mat)
                val split = BubblePopWatershedSplitter.split(mat, mask, boundary)
                splitMask = split.mask
                instanceMap = split.instances

                // Extract each labeled instance separately. Finding contours on the
                // union mask would reconnect diagonally touching watershed regions.
                val contours = ArrayList<MatOfPoint>()
                val hierarchy = Mat()
                val instanceMask = Mat()
                for (instanceId in 1..split.instanceCount) {
                    Core.compare(instanceMap, Scalar(instanceId.toDouble()), instanceMask, Core.CMP_EQ)
                    val localContours = ArrayList<MatOfPoint>()
                    Imgproc.findContours(instanceMask, localContours, hierarchy,
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    contours.addAll(localContours)
                }
                hierarchy.release()
                instanceMask.release()
                val resultBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(resultBitmap)
                val palette = intArrayOf(
                    0xFFFF453A.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt(),
                    0xFFFF9F0A.toInt(), 0xFFBF5AF2.toInt(), 0xFF64D2FF.toInt(),
                    0xFFFF375F.toInt(), 0xFFAC8E68.toInt()
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(2f, original.width / 500f)
                }
                var points = 0
                for ((index, contour) in contours.withIndex()) {
                    val pts = contour.toArray()
                    if (pts.size < 3) continue
                    points += pts.size
                    val path = Path().apply {
                        moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
                        for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
                        close()
                    }
                    paint.color = palette[index % palette.size]
                    canvas.drawPath(path, paint)
                }
                contours.forEach { it.release() }
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val finalBitmap = resultBitmap
                val inputCount = split.inputComponents
                val outputCount = split.outputComponents
                val splitCount = split.splitComponents
                main.post {
                    if (!isFinishing) {
                        preview.setImageBitmap(finalBitmap)
                        status.text = "Geometry watershed: исходных областей $inputCount, после разделения $outputCount, разделено крупных областей: $splitCount. Контуров: ${contours.size}. Вершин: $points. Время: ${elapsedMs} мс. Рельеф строится по distance transform + boundary, яркость рисунка не используется."
                    } else {
                        finalBitmap.recycle()
                    }
                }
            } catch (t: Throwable) {
                overlay?.recycle()
                main.post { status.text = "Ошибка watershed: ${t.javaClass.simpleName}: ${t.message}" }
            } finally {
                instanceMap?.release()
                splitMask?.release()
                boundary?.release()
                mask?.release()
                mat?.release()
                segmenter?.close()
                if (!inputBitmap.isRecycled) inputBitmap.recycle()
            }
        }
    }

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
