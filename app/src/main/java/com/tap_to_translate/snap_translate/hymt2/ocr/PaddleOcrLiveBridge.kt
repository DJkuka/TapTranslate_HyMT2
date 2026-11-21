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
        timeoutMs: Long,
        detectedRegions: List<DetectedRegion>?
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
        val future = java.util.concurrent.CompletableFuture<List<PaddleOcrTextGroup>>()
        executor.execute {
            try {
                val ocr = getEngine(app)
                val result = runBlocking { ocr.recognize(bitmap) }
                future.complete(logAndAssemble(result, bitmap.width, bitmap.height, detectedRegions))
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

    /**
     * Join only geometrically adjacent PP-OCR lines. Bubble identity is already
     * fixed by the post-OCR association, so lines from different balloons never
     * enter the same cluster. Large vertical gaps remain separate paragraphs.
     */
    private fun canJoinAdjacentLines(
        previous: PaddleOcrTextLine,
        current: PaddleOcrTextLine
    ): Boolean {
        val a = previous.getBounds()
        val b = current.getBounds()
        val minHeight = minOf(a.height(), b.height()).coerceAtLeast(1)
        val verticalOverlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val horizontalGap = maxOf(0, maxOf(a.left, b.left) - minOf(a.right, b.right))
        val verticalGap = maxOf(0, b.top - a.bottom)
        val horizontalOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val sameRow = verticalOverlap >= minHeight * 0.35 &&
            horizontalGap <= minHeight * 1.25
        val nextRow = verticalGap <= maxOf(18, (minHeight * 1.65).toInt()) &&
            (horizontalOverlap >= minOf(a.width(), b.width()) * 0.12 ||
                kotlin.math.abs(a.centerX() - b.centerX()) <= minHeight * 1.35)
        return sameRow || nextRow
    }

    private fun getEngine(context: Context): PaddleOCR {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            check(OpenCVUtils.init(context)) { "OpenCV initialization failed" }
            Log.i(TAG, "OpenCV initialization confirmed; abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            val ocrPrefs = context.getSharedPreferences("taptranslate_ocr_settings", Context.MODE_PRIVATE)
            val maxSideLimit = ocrPrefs.getInt("ocr_det_max_side_limit", 4000).coerceIn(256, 8192)
            val detThresh = ocrPrefs.getFloat("ocr_det_thresh", 0.20f).coerceIn(0.00f, 1.00f)
            val detBoxThresh = ocrPrefs.getFloat("ocr_det_box_thresh", 0.45f).coerceIn(0.00f, 1.00f)
            val detUnclipRatio = ocrPrefs.getFloat("ocr_det_unclip_ratio", 1.40f).coerceIn(0.10f, 10.00f)
            val recScoreThresh = ocrPrefs.getFloat("ocr_rec_score_thresh", 0.00f).coerceIn(0.00f, 1.00f)
            val detLimitSideLen = ocrPrefs.getInt("ocr_det_limit_side_len", 64).coerceIn(32, 4096)
            val detLimitType = (ocrPrefs.getString("ocr_det_limit_type", "min") ?: "min")
                .takeIf { it in setOf("min", "max", "resize_long") } ?: "min"
            val detImgMode = (ocrPrefs.getString("ocr_det_img_mode", "BGR") ?: "BGR")
                .takeIf { it == "BGR" || it == "RGB" } ?: "BGR"
            val detMaxCandidates = ocrPrefs.getInt("ocr_det_max_candidates", 3000).coerceIn(100, 20000)
            val detUseDilation = ocrPrefs.getBoolean("ocr_det_use_dilation", false)
            val detScoreMode = (ocrPrefs.getString("ocr_det_score_mode", "fast") ?: "fast")
                .takeIf { it == "fast" || it == "slow" } ?: "fast"
            val recBatchSize = (ocrPrefs.getString("ocr_rec_batch_size", "1") ?: "1")
                .toIntOrNull()?.coerceIn(1, 8) ?: 1
            val ortThreads = (ocrPrefs.getString("ocr_ort_threads", "2") ?: "2")
                .toIntOrNull()?.coerceIn(1, 8) ?: 2
            Log.i(TAG, "PP-OCRv6 config detThresh=" + detThresh +
                " detBoxThresh=" + detBoxThresh +
                " detUnclipRatio=" + detUnclipRatio +
                " recScoreThresh=" + recScoreThresh +
                " detLimitSideLen=" + detLimitSideLen +
                " detLimitType=" + detLimitType +
                " detImgMode=" + detImgMode +
                " detMaxSideLimit=" + maxSideLimit +
                " detMaxCandidates=" + detMaxCandidates +
                " detUseDilation=" + detUseDilation +
                " detScoreMode=" + detScoreMode +
                " recBatchSizeRequested=" + recBatchSize +
                " ortThreads=" + ortThreads)
            val created = runBlocking {
                PaddleOCR.create(
                    context = context,
                    config = PaddleOCRConfig(
                        detImgMode = detImgMode,
                        detLimitSideLen = detLimitSideLen,
                        detLimitType = detLimitType,
                        detMaxSideLimit = maxSideLimit,
                        detThresh = detThresh,
                        detBoxThresh = detBoxThresh,
                        detUnclipRatio = detUnclipRatio,
                        detMaxCandidates = detMaxCandidates,
                        detUseDilation = detUseDilation,
                        detScoreMode = detScoreMode,
                        recScoreThresh = recScoreThresh,
                        recBatchSize = recBatchSize
                    ),
                    engineConfig = EngineConfig(numThreads = ortThreads),
                    detModelAssetPath = "models/det/inference.onnx",
                    recModelAssetPath = "models/rec/inference.onnx",
                    recConfigAssetPath = "models/rec/inference.yml"
                )
            }
            engine = created
            Log.i(TAG, "PP-OCRv6 initialized; detMaxSideLimit=" + maxSideLimit +
                " recBatchSizeRequested=" + recBatchSize +
                " ortThreads=" + ortThreads +
                " cold_load_ms=" + created.coldLoadTimeMs)
            return created
        }
    }

    /**
     * PP-OCRv6 owns text detection geometry. Bubble detectors must never decide
     * which OCR lines belong to one translation block. Emit each PP-OCR detection
     * with its own rectangle; the host's existing text-layout stage may assemble
     * nearby lines using its own OCR geometry rules.
     */
    private fun logAndAssemble(
        result: OCRRunResult,
        width: Int,
        height: Int,
        detectedRegions: List<DetectedRegion>?
    ): List<PaddleOcrTextGroup> {
        Log.i(TAG, "frame=${width}x${height} lines=${result.lineCount} det_ms=${result.detectionTimeMs} rec_ms=${result.recognitionTimeMs} total_ms=${result.totalTimeMs}")
        val detectedLines = result.results.mapIndexedNotNull { i, item ->
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
            val left = floor(pts.minOf { it.x }.toDouble()).toInt().coerceIn(0, width)
            val top = floor(pts.minOf { it.y }.toDouble()).toInt().coerceIn(0, height)
            val right = ceil(pts.maxOf { it.x }.toDouble()).toInt().coerceIn(0, width)
            val bottom = ceil(pts.maxOf { it.y }.toDouble()).toInt().coerceIn(0, height)
            val bounds = Rect(left, top, right, bottom)
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                Log.w(TAG, "skip OCR detection with empty bounds index=$i text=$text")
                return@mapIndexedNotNull null
            }
            PaddleOcrTextLine(text, bounds, item.confidence.coerceIn(0f, 1f))
        }

        // OCR remains authoritative: BubblePop is consulted only after PP-OCR has
        // produced all text lines. It assigns lines to a layout region; it cannot
        // add, remove, split or rewrite recognized text.
        val bubbles = (detectedRegions ?: emptyList())
            .filter { it.getKind() == OcrRegionKind.BUBBLE_CONTAINER }
        val bubbleBounds = bubbles.map { region ->
            val b = region.getBounds()
            BubbleRegionMatcher.Bounds(b.left, b.top, b.right, b.bottom)
        }
        val bubbleLines = LinkedHashMap<String, MutableList<PaddleOcrTextLine>>()
        val freeGroups = ArrayList<PaddleOcrTextGroup>()
        var associated = 0
        detectedLines.forEachIndexed { index, line ->
            val b = line.getBounds()
            val container = BubbleRegionMatcher.findBestContainer(
                b.left, b.top, b.right, b.bottom, bubbleBounds)
            if (container >= 0 && container < bubbles.size) {
                val id = bubbles[container].getId()
                bubbleLines.getOrPut(id) { ArrayList() }.add(line)
                associated++
            } else {
                freeGroups.add(PaddleOcrTextGroup(
                    line.getText(), b, null, line.getConfidence(), 1, listOf(line)))
            }
            Log.i(TAG, "ocr_line[$index] bounds=${b.flattenToString()} bubble=${if (container >= 0 && container < bubbles.size) bubbles[container].getId() else "none"} text=${line.getText()}")
        }

        val groups = ArrayList<PaddleOcrTextGroup>()
        groups.addAll(freeGroups)
        for ((bubbleId, lines) in bubbleLines) {
            val ordered = lines.sortedWith(compareBy<PaddleOcrTextLine> { it.getBounds().top }
                .thenBy { it.getBounds().left })
            val clusters = ArrayList<MutableList<PaddleOcrTextLine>>()
            for (line in ordered) {
                val bounds = line.getBounds()
                val target = clusters.lastOrNull()
                val previous = target?.lastOrNull()
                if (target != null && previous != null && canJoinAdjacentLines(previous, line)) {
                    target.add(line)
                } else {
                    clusters.add(arrayListOf(line))
                }
            }
            for (cluster in clusters) {
                val union = Rect(cluster.first().getBounds())
                cluster.drop(1).forEach { union.union(it.getBounds()) }
                val text = cluster.map { it.getText() }.joinToString("\n")
                val confidence = cluster.map { it.getConfidence() }.average().toFloat()
                groups.add(PaddleOcrTextGroup(
                    text, union, bubbleId, confidence, cluster.size, cluster))
            }
        }
        groups.sortWith(compareBy<PaddleOcrTextGroup> { it.getBounds().top }
            .thenBy { it.getBounds().left })
        val skipped = result.results.size - detectedLines.size
        Log.i(TAG, "text_geometry_summary detections=${result.results.size} lines=${detectedLines.size} layout_blocks=${groups.size} bubble_lines=${associated} bubble_groups=${bubbleLines.size} skipped=${skipped} association=post_ocr_only")
        return groups
    }
}