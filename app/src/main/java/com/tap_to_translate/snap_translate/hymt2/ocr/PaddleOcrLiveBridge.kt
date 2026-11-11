package com.tap_to_translate.snap_translate.hymt2.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.util.OpenCVUtils
import java.util.Locale
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/** Diagnostic-only PP-OCRv6 bridge. Host ML Kit remains authoritative in this prototype. */
object PaddleOcrLiveBridge {
    private const val TAG = "TapTranslatePaddle"
    private val busy = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var engine: PaddleOCR? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "taptranslate-paddle-ocr").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    @JvmStatic fun submit(context: Context?, source: Bitmap?, detectedRegions: List<DetectedRegion>?) {
        if (context == null || source == null || source.isRecycled || !busy.compareAndSet(false, true)) return
        val bitmap = try { source.copy(Bitmap.Config.ARGB_8888, false) } catch (t: Throwable) {
            busy.set(false); Log.w(TAG, "frame copy failed", t); return
        }
        if (bitmap == null) { busy.set(false); return }
        val app = context.applicationContext ?: context
        val regions = detectedRegions?.toList() ?: emptyList()
        executor.execute {
            try {
                val ocr = getEngine(app)
                log(runBlocking { ocr.recognize(bitmap) }, bitmap.width, bitmap.height, regions)
            } catch (t: Throwable) {
                Log.e(TAG, "Paddle inference failed; ML Kit output remains unchanged", t)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                busy.set(false)
            }
        }
    }

    private fun getEngine(context: Context): PaddleOCR {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            check(OpenCVUtils.init(context)) { "OpenCV initialization failed" }
            Log.i(TAG, "OpenCV initialization confirmed; abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            val created = runBlocking {
                PaddleOCR.create(
                    context = context,
                    config = PaddleOCRConfig(detThresh = 0.20f, detBoxThresh = 0.45f,
                        detUnclipRatio = 1.4f, recScoreThresh = 0f, recBatchSize = 1),
                    engineConfig = EngineConfig(numThreads = 2),
                    detModelAssetPath = "models/det/inference.onnx",
                    recModelAssetPath = "models/rec/inference.onnx",
                    recConfigAssetPath = "models/rec/inference.yml"
                )
            }
            engine = created
            Log.i(TAG, "PP-OCRv6 initialized; cold_load_ms=${created.coldLoadTimeMs}")
            return created
        }
    }

    private fun log(result: OCRRunResult, width: Int, height: Int, regions: List<DetectedRegion>) {
        Log.i(TAG, "frame=${width}x${height} lines=${result.lineCount} det_ms=${result.detectionTimeMs} rec_ms=${result.recognitionTimeMs} total_ms=${result.totalTimeMs}")
        data class Line(val index: Int, val confidence: Float, val bounds: Rect, val text: String, val bubbleId: String?)
        val bubbleRegions = regions.filter { it.kind == OcrRegionKind.BUBBLE_CONTAINER }
        val bubbleBounds = bubbleRegions.map { it.bounds }.map {
            BubbleRegionMatcher.Bounds(it.left, it.top, it.right, it.bottom)
        }
        val lines = result.results.mapIndexed { i, item ->
            val pts = item.box.points
            val l = floor(pts.minOf { it.x }.toDouble()).toInt().coerceIn(0, width)
            val t = floor(pts.minOf { it.y }.toDouble()).toInt().coerceIn(0, height)
            val r = ceil(pts.maxOf { it.x }.toDouble()).toInt().coerceIn(0, width)
            val b = ceil(pts.maxOf { it.y }.toDouble()).toInt().coerceIn(0, height)
            val rect = Rect(l, t, r, b)
            val containerIndex = BubbleRegionMatcher.findBestContainer(l, t, r, b, bubbleBounds)
            val bubbleId = if (containerIndex >= 0) bubbleRegions[containerIndex].id else null
            val text = item.text.replace('\n', ' ').replace('\r', ' ').take(200)
            Line(i, item.confidence, rect, text, bubbleId)
        }
        lines.forEach { line ->
            Log.i(TAG, "line[${line.index}] conf=${line.confidence} bubble=${line.bubbleId ?: "FREE_OR_UNMATCHED"} bounds=${line.bounds.flattenToString()} text=${line.text}")
        }
        val grouped = lines.filter { it.bubbleId != null }.groupBy { it.bubbleId!! }
        grouped.forEach { (bubbleId, bubbleLines) ->
            val ordered = bubbleLines.sortedWith(compareBy<Line> { it.bounds.top }.thenBy { it.bounds.left })
            val utterance = ordered.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
            Log.i(TAG, "bubble_group id=${bubbleId} lines=${ordered.size} text=${utterance}")
        }
        val freeCount = lines.count { it.bubbleId == null }
        Log.i(TAG, "grouping_summary bubbles=${grouped.size} grouped_lines=${lines.size - freeCount} free_or_unmatched=${freeCount}")
    }
}
