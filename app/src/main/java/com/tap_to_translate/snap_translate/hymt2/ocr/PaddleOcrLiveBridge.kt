package com.tap_to_translate.snap_translate.hymt2.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
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
    private const val PERF_TAG = "TapTranslatePerf"
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
        val bridgeStartNs = SystemClock.elapsedRealtimeNanos()
        val frameId = Integer.toHexString(System.identityHashCode(source))
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "Paddle busy; ML Kit fallback retained")
            Log.i(PERF_TAG, "event=paddle_skipped frame=$frameId reason=busy")
            return null
        }
        val copyStartNs = SystemClock.elapsedRealtimeNanos()
        val bitmap = try { source.copy(Bitmap.Config.ARGB_8888, false) } catch (t: Throwable) {
            busy.set(false); Log.w(TAG, "frame copy failed", t)
            Log.i(PERF_TAG, "event=paddle_copy_failed frame=$frameId")
            return null
        } ?: run { busy.set(false); Log.i(PERF_TAG, "event=paddle_copy_failed frame=$frameId reason=null_bitmap"); return null }
        val copyMs = (SystemClock.elapsedRealtimeNanos() - copyStartNs) / 1_000_000L
        val app = context.applicationContext ?: context
        val regions = detectedRegions?.toList() ?: emptyList()
        val future = java.util.concurrent.CompletableFuture<List<PaddleOcrTextGroup>>()
        val submittedNs = SystemClock.elapsedRealtimeNanos()
        Log.i(PERF_TAG, "event=paddle_submitted frame=$frameId width=${bitmap.width} height=${bitmap.height} region_count=${regions.size} bitmap_copy_ms=$copyMs")
        executor.execute {
            val workerStartNs = SystemClock.elapsedRealtimeNanos()
            val queueWaitMs = (workerStartNs - submittedNs) / 1_000_000L
            Log.i(PERF_TAG, "event=paddle_worker_started frame=$frameId queue_wait_ms=$queueWaitMs")
            try {
                val engineStartNs = SystemClock.elapsedRealtimeNanos()
                val ocr = getEngine(app)
                val engineAcquireMs = (SystemClock.elapsedRealtimeNanos() - engineStartNs) / 1_000_000L
                val ocrStartNs = SystemClock.elapsedRealtimeNanos()
                val result = runBlocking { ocr.recognize(bitmap) }
                val ocrWallMs = (SystemClock.elapsedRealtimeNanos() - ocrStartNs) / 1_000_000L
                Log.i(PERF_TAG, "event=ppocr_stages frame=$frameId engine_acquire_ms=$engineAcquireMs cold_model_load_ms=${result.coldLoadTimeMs} " +
                    "det_preprocess_ms=${result.detPreprocessMs} det_inference_ms=${result.detInferenceMs} det_postprocess_ms=${result.detPostprocessMs} " +
                    "detected_lines=${result.lineCount} rec_preprocess_ms=${result.recPreprocessMs} rec_inference_ms=${result.recInferenceMs} " +
                    "rec_postprocess_ms=${result.recPostprocessMs} rec_batch_calls=${result.recInputShapes.size} " +
                    "rec_batch_shapes=${result.recInputShapes.joinToString(";") { it.joinToString("x") }} " +
                    "pipeline_overhead_ms=${result.pipelineOverheadMs} ocr_core_ms=${result.totalTimeMs} ocr_wall_ms=$ocrWallMs")
                val assembleStartNs = SystemClock.elapsedRealtimeNanos()
                val groups = logAndAssemble(result, bitmap.width, bitmap.height, regions)
                val assembleMs = (SystemClock.elapsedRealtimeNanos() - assembleStartNs) / 1_000_000L
                Log.i(PERF_TAG, "event=paddle_assemble frame=$frameId lines=${result.lineCount} output_groups=${groups.size} assembly_ms=$assembleMs")
                future.complete(groups)
            } catch (t: Throwable) {
                Log.e(TAG, "Paddle inference failed; ML Kit output remains unchanged", t)
                future.completeExceptionally(t)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                busy.set(false)
            }
        }
        return try {
            val waitStartNs = SystemClock.elapsedRealtimeNanos()
            val result = future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            val waitMs = (SystemClock.elapsedRealtimeNanos() - waitStartNs) / 1_000_000L
            val totalMs = (SystemClock.elapsedRealtimeNanos() - bridgeStartNs) / 1_000_000L
            Log.i(PERF_TAG, "event=paddle_bridge_complete frame=$frameId groups=${result.size} caller_wait_ms=$waitMs bridge_total_ms=$totalMs")
            result
        } catch (t: Throwable) {
            val totalMs = (SystemClock.elapsedRealtimeNanos() - bridgeStartNs) / 1_000_000L
            Log.w(TAG, "Paddle result unavailable; ML Kit fallback retained", t)
            Log.i(PERF_TAG, "event=paddle_bridge_failed frame=$frameId bridge_total_ms=$totalMs timeout_ms=$timeoutMs")
            null
        }
    }

    private fun getEngine(context: Context): PaddleOCR {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            check(OpenCVUtils.init(context)) { "OpenCV initialization failed" }
            Log.i(TAG, "OpenCV initialization confirmed; abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            val ocrPrefs = context.getSharedPreferences("taptranslate_ocr_settings", Context.MODE_PRIVATE)
            val maxSideLimit = ocrPrefs.getInt("ocr_det_max_side_limit", 4000).coerceIn(1280, 4000)
            val detThresh = ocrPrefs.getFloat("ocr_det_thresh", 0.20f).coerceIn(0.05f, 0.60f)
            val detBoxThresh = ocrPrefs.getFloat("ocr_det_box_thresh", 0.45f).coerceIn(0.10f, 0.90f)
            val detUnclipRatio = ocrPrefs.getFloat("ocr_det_unclip_ratio", 1.40f).coerceIn(1.00f, 2.00f)
            val recScoreThresh = ocrPrefs.getFloat("ocr_rec_score_thresh", 0.00f).coerceIn(0.00f, 1.00f)
            Log.i(TAG, "PP-OCRv6 config detThresh=" + detThresh +
                " detBoxThresh=" + detBoxThresh +
                " detUnclipRatio=" + detUnclipRatio +
                " recScoreThresh=" + recScoreThresh +
                " detMaxSideLimit=" + maxSideLimit)
            val created = runBlocking {
                PaddleOCR.create(
                    context = context,
                    config = PaddleOCRConfig(
                        detThresh = detThresh,
                        detBoxThresh = detBoxThresh,
                        detUnclipRatio = detUnclipRatio,
                        recScoreThresh = recScoreThresh,
                        recBatchSize = 1,
                        detMaxSideLimit = maxSideLimit
                    ),
                    engineConfig = EngineConfig(numThreads = 2),
                    detModelAssetPath = "models/det/inference.onnx",
                    recModelAssetPath = "models/rec/inference.onnx",
                    recConfigAssetPath = "models/rec/inference.yml"
                )
            }
            engine = created
            Log.i(TAG, "PP-OCRv6 initialized; detMaxSideLimit=" + maxSideLimit + " cold_load_ms=" + created.coldLoadTimeMs)
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
        val lines = result.results.mapIndexedNotNull { i, item ->
            val text = item.text.replace('\n', ' ').replace('\r', ' ').take(200).trim()
            if (text.isEmpty()) {
                Log.w(TAG, "skip empty OCR detection index=$i conf=${item.confidence}")
                return@mapIndexedNotNull null
            }
            val pts = item.box.points
            if (pts.isEmpty()) {
                Log.w(TAG, "skip OCR detection with empty polygon index=$i text=$text")
                return@mapIndexedNotNull null
            }
            val l = floor(pts.minOf { it.x }.toDouble()).toInt().coerceIn(0, width)
            val t = floor(pts.minOf { it.y }.toDouble()).toInt().coerceIn(0, height)
            val r = ceil(pts.maxOf { it.x }.toDouble()).toInt().coerceIn(0, width)
            val b = ceil(pts.maxOf { it.y }.toDouble()).toInt().coerceIn(0, height)
            val rect = Rect(l, t, r, b)
            if (rect.width() <= 0 || rect.height() <= 0) {
                Log.w(TAG, "skip OCR detection with empty bounds index=$i text=$text")
                return@mapIndexedNotNull null
            }
            val containerIndex = BubbleRegionMatcher.findBestContainer(l, t, r, b, bubbleBounds)
            val bubbleId = if (containerIndex >= 0) bubbleRegions[containerIndex].id else null
            Line(i, item.confidence, rect, text, bubbleId)
        }
        val emptyResultCount = result.results.count {
            it.text.replace('\n', ' ').replace('\r', ' ').isBlank()
        }
        if (emptyResultCount > 0) {
            Log.w(TAG, "ignored empty OCR detections=${emptyResultCount}; valid_lines=${lines.size}")
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
