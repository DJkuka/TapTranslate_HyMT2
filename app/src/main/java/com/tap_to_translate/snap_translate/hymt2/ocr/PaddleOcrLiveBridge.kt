package com.tap_to_translate.snap_translate.hymt2.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.util.OpenCVUtils
import java.util.Locale
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/** PP-OCRv6 recognition adapter. RT-DETR supplies bubble boundaries; ML Kit is only a free-text fallback. */
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

    /**
     * Runs on the host OCR callback worker (not the UI thread). The caller may wait
     * for a bounded time; a timeout leaves the original ML Kit result untouched.
     */
    @JvmStatic fun recognizeBlocking(
        context: Context?,
        source: Bitmap?,
        detectedRegions: List<DetectedRegion>?,
        timeoutMs: Long
    ): List<PaddleOcrTextGroup>? {
        if (context == null || source == null || source.isRecycled) return null
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "Paddle busy; ML Kit fallback retained")
            return null
        }
        val bitmap = try { source.copy(Bitmap.Config.ARGB_8888, false) } catch (t: Throwable) {
            busy.set(false); Log.w(TAG, "frame copy failed", t); return null
        } ?: run { busy.set(false); return null }
        val app = context.applicationContext ?: context
        val regions = detectedRegions?.toList() ?: emptyList()
        val future = java.util.concurrent.CompletableFuture<List<PaddleOcrTextGroup>>()
        executor.execute {
            try {
                val ocr = getEngine(app)
                val result = runBlocking { ocr.recognize(bitmap) }
                future.complete(logAndAssemble(result, bitmap.width, bitmap.height, regions))
            } catch (t: Throwable) {
                Log.e(TAG, "Paddle inference failed; ML Kit output remains unchanged", t)
                future.completeExceptionally(t)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                busy.set(false)
            }
        }
        return try {
            future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "Paddle result unavailable; ML Kit fallback retained", t)
            null
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

    private fun logAndAssemble(result: OCRRunResult, width: Int, height: Int, regions: List<DetectedRegion>): List<PaddleOcrTextGroup> {
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
        val utteranceGroups = mutableListOf<PaddleOcrTextGroup>()
        grouped.forEach { (bubbleId, bubbleLines) ->
            val ordered = bubbleLines.sortedWith(compareBy<Line> { it.bounds.top }.thenBy { it.bounds.left })
            val utterance = ordered.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
            val union = Rect(ordered.first().bounds)
            ordered.drop(1).forEach { union.union(it.bounds) }
            val confidence = ordered.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)
            val lineModels = ordered.map { PaddleOcrTextLine(it.text, it.bounds, it.confidence) }
            utteranceGroups.add(PaddleOcrTextGroup(
                utterance, union, bubbleId, confidence, ordered.size, lineModels
            ))
            Log.i(TAG, "bubble_group id=${bubbleId} lines=${ordered.size} text=${utterance}")
        }
        // Free/unmatched text is deliberately kept as independent blocks. A later
        // renderer can place these underneath bubble overlays without losing SFX/signage.
        lines.filter { it.bubbleId == null }.forEach { line ->
            utteranceGroups.add(PaddleOcrTextGroup(
                line.text, line.bounds, null, line.confidence, 1,
                listOf(PaddleOcrTextLine(line.text, line.bounds, line.confidence))
            ))
        }
        val freeCount = lines.count { it.bubbleId == null }
        Log.i(TAG, "grouping_summary bubbles=${grouped.size} grouped_lines=${lines.size - freeCount} free_or_unmatched=${freeCount} output_groups=${utteranceGroups.size}")
        utteranceGroups.forEachIndexed { i, group ->
            Log.i(TAG, "host_group[${i}] bubble=${group.bubbleId ?: "FREE"} lines=${group.lineCount} conf=${group.confidence} bounds=${group.bounds.flattenToString()} text=${group.text}")
        }
        return utteranceGroups
    }
}
