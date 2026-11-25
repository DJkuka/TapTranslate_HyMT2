package com.tap_to_translate.snap_translate.hymt2.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
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
import kotlin.math.min
import kotlin.math.roundToInt

/** PP-OCRv6 recognition adapter. RT-DETR supplies bubble boundaries; ML Kit is only a free-text fallback. */
object PaddleOcrLiveBridge {
    private const val TAG = "TapTranslatePaddle"
    // Keep the whole-frame pass for free text and as a fallback for bubbles.
    private const val DETECTION_UPSCALE = 2
    // Bubble crops are packed into a 2x2 contact sheet. Each bubble gets a much
    // larger share of the detector's input pixels, approximating a user zoom.
    private const val MOSAIC_COLUMNS = 2
    private const val MOSAIC_ROWS = 2
    private const val MOSAIC_LONG_SIDE = 4000
    private const val MOSAIC_GUTTER = 24
    private const val MAX_BUBBLE_UPSCALE = 10.0f
    private const val MIN_BUBBLE_UPSCALE = 2.2f
    private const val MAX_BUBBLE_CROPS = 8

    private val busy = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var engine: PaddleOCR? = null
    @Volatile private var activeMaxSideLimit = 4000
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "taptranslate-paddle-ocr").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    private data class CoordinateTransform(
        val outputBounds: Rect,
        val sourceBounds: Rect,
        val scaleX: Float,
        val scaleY: Float,
        val bubbleId: String? = null
    )

    private data class BubbleCropCandidate(
        val bubbleId: String,
        val sourceBounds: Rect,
        val estimatedScale: Float
    )

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
                val originalWidth = bitmap.width
                val originalHeight = bitmap.height

                // Pass 1: whole frame. This preserves free text and provides a
                // fallback for any bubble that the regional pass cannot read.
                val detectionBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    (originalWidth * DETECTION_UPSCALE).coerceAtLeast(1),
                    (originalHeight * DETECTION_UPSCALE).coerceAtLeast(1),
                    true
                )
                val fullFrameGroups = try {
                    Log.i(TAG, "PP-OCR full-frame input upscale=${DETECTION_UPSCALE}x original=${originalWidth}x${originalHeight} detection=${detectionBitmap.width}x${detectionBitmap.height}")
                    val result = runBlocking { ocr.recognize(detectionBitmap) }
                    val transform = CoordinateTransform(
                        outputBounds = Rect(0, 0, detectionBitmap.width, detectionBitmap.height),
                        sourceBounds = Rect(0, 0, originalWidth, originalHeight),
                        scaleX = detectionBitmap.width.toFloat() / originalWidth.toFloat(),
                        scaleY = detectionBitmap.height.toFloat() / originalHeight.toFloat()
                    )
                    logAndAssemble(
                        result, originalWidth, originalHeight, regions,
                        listOf(transform), "full-frame"
                    )
                } finally {
                    if (!detectionBitmap.isRecycled) detectionBitmap.recycle()
                }

                // Pass 2: re-run only small RT-DETR bubble regions on a 2x2 mosaic.
                // A single inference can therefore inspect four enlarged bubbles.
                val regionalGroups = runBubbleMosaics(ocr, bitmap, regions)
                val fullByBubble = fullFrameGroups.filter { it.bubbleId != null }
                    .groupBy { it.bubbleId!! }
                val regionalByBubble = regionalGroups.filter { it.bubbleId != null }
                    .groupBy { it.bubbleId!! }
                val bubbleIds = (fullByBubble.keys + regionalByBubble.keys).distinct()
                val selectedBubbleGroups = bubbleIds.mapNotNull { id ->
                    val candidates = (fullByBubble[id] ?: emptyList()) +
                        (regionalByBubble[id] ?: emptyList())
                    candidates.maxByOrNull { groupQuality(it) }
                }
                val freeTextGroups = fullFrameGroups.filter { it.bubbleId == null }
                val combined = (freeTextGroups + selectedBubbleGroups)
                    .sortedWith(compareBy<PaddleOcrTextGroup> { it.bounds.top }.thenBy { it.bounds.left })

                Log.i(TAG, "PP-OCR merge summary full_groups=${fullFrameGroups.size} regional_groups=${regionalGroups.size} bubbles_reprocessed=${regionalByBubble.size} final_groups=${combined.size}")
                combined.forEachIndexed { i, group ->
                    Log.i(TAG, "final_group[${i}] bubble=${group.bubbleId ?: "FREE"} lines=${group.lineCount} conf=${group.confidence} bounds=${group.bounds.flattenToString()} text=${group.text}")
                }
                future.complete(combined)
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

    private fun groupQuality(group: PaddleOcrTextGroup): Float {
        // Prefer the pass that recovered more characters/lines; confidence breaks ties.
        return group.text.length.toFloat() + group.lineCount * 6.0f + group.confidence * 2.0f
    }

    private fun runBubbleMosaics(
        ocr: PaddleOCR,
        source: Bitmap,
        regions: List<DetectedRegion>
    ): List<PaddleOcrTextGroup> {
        val containers = regions.filter { it.kind == OcrRegionKind.BUBBLE_CONTAINER }
            .distinctBy { it.id }
        if (containers.isEmpty()) {
            Log.i(TAG, "PP-OCR regional pass skipped: no RT-DETR bubble containers")
            return emptyList()
        }

        val maxSide = min(activeMaxSideLimit, MOSAIC_LONG_SIDE)
        if (maxSide < 1024) {
            Log.w(TAG, "PP-OCR regional pass skipped: detMaxSideLimit too small=$maxSide")
            return emptyList()
        }
        val longSide = (maxSide / 32) * 32
        val aspect = source.width.toDouble() / source.height.toDouble()
        var mosaicWidth = if (aspect >= 1.0) longSide else
            ((longSide * aspect / 32.0).roundToInt() * 32).coerceAtLeast(32)
        var mosaicHeight = if (aspect >= 1.0)
            ((longSide / aspect / 32.0).roundToInt() * 32).coerceAtLeast(32)
        else longSide
        val largest = maxOf(mosaicWidth, mosaicHeight)
        if (largest > longSide) {
            val correction = longSide.toDouble() / largest.toDouble()
            mosaicWidth = ((mosaicWidth * correction / 32.0).toInt() * 32).coerceAtLeast(32)
            mosaicHeight = ((mosaicHeight * correction / 32.0).toInt() * 32).coerceAtLeast(32)
        }

        val cellWidth = mosaicWidth / MOSAIC_COLUMNS
        val cellHeight = mosaicHeight / MOSAIC_ROWS
        val innerWidth = cellWidth - 2 * MOSAIC_GUTTER
        val innerHeight = cellHeight - 2 * MOSAIC_GUTTER
        if (innerWidth <= 0 || innerHeight <= 0) return emptyList()

        val candidates = containers.mapNotNull { region ->
            val bounds = region.bounds
            val padX = (bounds.width() * 0.04f).roundToInt().coerceIn(6, 32)
            val padY = (bounds.height() * 0.04f).roundToInt().coerceIn(6, 32)
            val crop = Rect(
                bounds.left - padX, bounds.top - padY,
                bounds.right + padX, bounds.bottom + padY
            )
            if (!crop.intersect(0, 0, source.width, source.height)) return@mapNotNull null
            if (crop.width() <= 0 || crop.height() <= 0) return@mapNotNull null
            val scale = min(
                min(innerWidth.toFloat() / crop.width(), innerHeight.toFloat() / crop.height()),
                MAX_BUBBLE_UPSCALE
            )
            if (scale < MIN_BUBBLE_UPSCALE) return@mapNotNull null
            BubbleCropCandidate(region.id, crop, scale)
        }.sortedByDescending { it.estimatedScale }.take(MAX_BUBBLE_CROPS)

        if (candidates.isEmpty()) {
            Log.i(TAG, "PP-OCR regional pass skipped: no bubble crop can exceed ${MIN_BUBBLE_UPSCALE}x")
            return emptyList()
        }
        Log.i(TAG, "PP-OCR regional pass candidates=${candidates.size} containers=${containers.size} mosaic=${mosaicWidth}x${mosaicHeight} batches=${(candidates.size + 3) / 4}")

        val output = mutableListOf<PaddleOcrTextGroup>()
        val batches = candidates.chunked(MOSAIC_COLUMNS * MOSAIC_ROWS)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        batches.forEachIndexed { batchIndex, batch ->
            val mosaic = try {
                Bitmap.createBitmap(mosaicWidth, mosaicHeight, Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                Log.e(TAG, "PP-OCR regional mosaic allocation failed", t)
                return@forEachIndexed
            }
            try {
                val canvas = Canvas(mosaic)
                canvas.drawColor(Color.WHITE)
                val transforms = mutableListOf<CoordinateTransform>()
                batch.forEachIndexed { cellIndex, candidate ->
                    val column = cellIndex % MOSAIC_COLUMNS
                    val row = cellIndex / MOSAIC_COLUMNS
                    val cellLeft = column * cellWidth
                    val cellTop = row * cellHeight
                    val scale = min(
                        min(innerWidth.toFloat() / candidate.sourceBounds.width(),
                            innerHeight.toFloat() / candidate.sourceBounds.height()),
                        MAX_BUBBLE_UPSCALE
                    )
                    val targetWidth = (candidate.sourceBounds.width() * scale).roundToInt().coerceAtLeast(1)
                    val targetHeight = (candidate.sourceBounds.height() * scale).roundToInt().coerceAtLeast(1)
                    val left = cellLeft + (cellWidth - targetWidth) / 2
                    val top = cellTop + (cellHeight - targetHeight) / 2
                    val destination = Rect(left, top, left + targetWidth, top + targetHeight)
                    canvas.drawBitmap(source, candidate.sourceBounds, destination, paint)
                    transforms.add(
                        CoordinateTransform(
                            outputBounds = destination,
                            sourceBounds = Rect(candidate.sourceBounds),
                            scaleX = destination.width().toFloat() / candidate.sourceBounds.width().toFloat(),
                            scaleY = destination.height().toFloat() / candidate.sourceBounds.height().toFloat(),
                            bubbleId = candidate.bubbleId
                        )
                    )
                    Log.i(TAG, "PP-OCR bubble crop id=${candidate.bubbleId} source=${candidate.sourceBounds.flattenToString()} scale=${"%.2f".format(Locale.US, scale)}x mosaic_cell=${destination.flattenToString()}")
                }

                val result = runBlocking { ocr.recognize(mosaic) }
                Log.i(TAG, "PP-OCR bubble mosaic batch=${batchIndex + 1}/${batches.size} input=${mosaic.width}x${mosaic.height} det_shape=${result.detInputShape} lines=${result.lineCount} det_ms=${result.detectionTimeMs} rec_ms=${result.recognitionTimeMs} total_ms=${result.totalTimeMs}")
                val mapped = logAndAssemble(
                    result, source.width, source.height, regions, transforms,
                    "bubble-mosaic-${batchIndex + 1}"
                )
                val batchIds = batch.map { it.bubbleId }.toSet()
                output.addAll(mapped.filter { it.bubbleId != null && batchIds.contains(it.bubbleId) })
            } catch (t: Throwable) {
                Log.e(TAG, "PP-OCR bubble mosaic batch=${batchIndex + 1} failed; full-frame results retained", t)
            } finally {
                if (!mosaic.isRecycled) mosaic.recycle()
            }
        }
        return output
    }

    /** Execute a real PP-OCRv6 detector + recognizer pass using the same engine as live translation. */
    @JvmStatic
    fun diagnosticSelfTest(context: Context?, source: Bitmap?): String {
        if (context == null) throw IllegalArgumentException("Контекст приложения недоступен")
        if (source == null || source.isRecycled) throw IllegalArgumentException("Тестовое изображение недоступно")
        if (!busy.compareAndSet(false, true)) return "BUSY: PP-OCRv6 занят текущим распознаванием; повтори позже"
        var bitmap: Bitmap? = null
        try {
            val testBitmap = source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Не удалось подготовить тестовое изображение")
            bitmap = testBitmap
            val ocr = getEngine(context.applicationContext ?: context)
            val result = runBlocking { ocr.recognize(testBitmap) }
            val state = if (result.lineCount > 0) "PASS: распознано строк=${result.lineCount}"
                else "INFERENCE_OK: инференс завершён, но тестовые строки не обнаружены"
            val report = state + "; total_ms=${result.totalTimeMs}; det_ms=${result.detectionTimeMs}; rec_ms=${result.recognitionTimeMs}; det_shape=${result.detInputShape}; cold_load_ms=${result.coldLoadTimeMs}"
            Log.i(TAG, "PP-OCR diagnostic: " + report)
            return report
        } catch (t: Throwable) {
            Log.e(TAG, "PP-OCR diagnostic failed", t)
            throw IllegalStateException("PP-OCRv6 test failed: ${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            busy.set(false)
        }
    }

    private fun getEngine(context: Context): PaddleOCR {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            check(OpenCVUtils.init(context)) { "OpenCV initialization failed" }
            Log.i(TAG, "OpenCV initialization confirmed; abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            val ocrPrefs = context.getSharedPreferences("taptranslate_ocr_settings", Context.MODE_PRIVATE)
            val maxSideLimit = ocrPrefs.getInt("ocr_det_max_side_limit", 4000).coerceIn(256, 8192)
            activeMaxSideLimit = maxSideLimit
            val detThresh = ocrPrefs.getFloat("ocr_det_thresh", 0.20f).coerceIn(0.00f, 1.00f)
            val detBoxThresh = ocrPrefs.getFloat("ocr_det_box_thresh", 0.45f).coerceIn(0.00f, 1.00f)
            val detUnclipRatio = ocrPrefs.getFloat("ocr_det_unclip_ratio", 1.40f).coerceIn(0.10f, 10.00f)
            val recScoreThresh = ocrPrefs.getFloat("ocr_rec_score_thresh", 0.00f).coerceIn(0.00f, 1.00f)
            val detImgMode = (ocrPrefs.getString("ocr_det_img_mode", "BGR") ?: "BGR")
                .uppercase(Locale.ROOT).let { if (it == "RGB" || it == "BGR") it else "BGR" }
            val detLimitSideLen = ocrPrefs.getInt("ocr_det_limit_side_len", 64).coerceIn(32, 4096)
            val detLimitType = (ocrPrefs.getString("ocr_det_limit_type", "min") ?: "min")
                .lowercase(Locale.ROOT).let { if (it in setOf("min", "max", "resize_long")) it else "min" }
            val detMaxCandidates = ocrPrefs.getInt("ocr_det_max_candidates", 3000).coerceIn(100, 20000)
            val detUseDilation = ocrPrefs.getBoolean("ocr_det_use_dilation", false)
            val detScoreMode = (ocrPrefs.getString("ocr_det_score_mode", "fast") ?: "fast")
                .lowercase(Locale.ROOT).let { if (it == "fast" || it == "slow") it else "fast" }
            val recBatchSize = (ocrPrefs.getString("ocr_rec_batch_size", "1") ?: "1").toIntOrNull()?.coerceIn(1, 8) ?: 1
            val ortThreads = (ocrPrefs.getString("ocr_ort_threads", "2") ?: "2").toIntOrNull()?.coerceIn(1, 8) ?: 2
            val recPreprocessScale = (ocrPrefs.getString("ocr_rec_preprocess_scale", "1.0") ?: "1.0")
                .toFloatOrNull()?.let { if (it == 1.0f || it == 1.5f || it == 2.0f) it else 1.0f } ?: 1.0f
            Log.i(TAG, "PP-OCRv6 config detImgMode=" + detImgMode +
                " detLimitSideLen=" + detLimitSideLen + " detLimitType=" + detLimitType +
                " detThresh=" + detThresh + " detBoxThresh=" + detBoxThresh +
                " detUnclipRatio=" + detUnclipRatio + " detMaxCandidates=" + detMaxCandidates +
                " detUseDilation=" + detUseDilation + " detScoreMode=" + detScoreMode +
                " recScoreThresh=" + recScoreThresh + " recBatchSize=" + recBatchSize +
                " recPreprocessScale=" + recPreprocessScale + " ortThreads=" + ortThreads +
                " detMaxSideLimit=" + maxSideLimit)
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
                        recBatchSize = recBatchSize,
                        recPreprocessScale = recPreprocessScale,
                        detBoxType = "quad"
                    ),
                    engineConfig = EngineConfig(numThreads = ortThreads),
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

    private fun logAndAssemble(
        result: OCRRunResult,
        width: Int,
        height: Int,
        regions: List<DetectedRegion>,
        transforms: List<CoordinateTransform>,
        passName: String
    ): List<PaddleOcrTextGroup> {
        Log.i(TAG, "frame=${width}x${height} pass=${passName} det_shape=${result.detInputShape} lines=${result.lineCount} det_ms=${result.detectionTimeMs} rec_ms=${result.recognitionTimeMs} total_ms=${result.totalTimeMs}")
        data class Line(val index: Int, val confidence: Float, val bounds: Rect, val text: String, val bubbleId: String?)
        val bubbleRegions = regions.filter { it.kind == OcrRegionKind.BUBBLE_CONTAINER }
        val bubbleBounds = bubbleRegions.map { it.bounds }.map {
            BubbleRegionMatcher.Bounds(it.left, it.top, it.right, it.bottom)
        }
        val lines = result.results.mapIndexedNotNull { i, item ->
            val text = item.text.replace('\n', ' ').replace('\r', ' ').take(200).trim()
            if (text.isEmpty()) {
                Log.w(TAG, "skip empty OCR detection pass=${passName} index=${i} conf=${item.confidence}")
                return@mapIndexedNotNull null
            }
            val pts = item.box.points
            if (pts.isEmpty()) {
                Log.w(TAG, "skip OCR detection with empty polygon pass=${passName} index=${i} text=${text}")
                return@mapIndexedNotNull null
            }
            val centerX = pts.map { it.x }.average().roundToInt()
            val centerY = pts.map { it.y }.average().roundToInt()
            val transform = transforms.firstOrNull {
                it.outputBounds.contains(centerX, centerY)
            }
            if (transform == null || transform.scaleX <= 0f || transform.scaleY <= 0f) {
                Log.w(TAG, "skip OCR box outside source transform pass=${passName} index=${i} text=${text}")
                return@mapIndexedNotNull null
            }
            val mapped = pts.map { point ->
                PointF(
                    (transform.sourceBounds.left +
                        (point.x - transform.outputBounds.left) / transform.scaleX)
                        .coerceIn(0f, width.toFloat()),
                    (transform.sourceBounds.top +
                        (point.y - transform.outputBounds.top) / transform.scaleY)
                        .coerceIn(0f, height.toFloat())
                )
            }
            val l = floor(mapped.minOf { it.x }.toDouble()).toInt().coerceIn(0, width)
            val t = floor(mapped.minOf { it.y }.toDouble()).toInt().coerceIn(0, height)
            val r = ceil(mapped.maxOf { it.x }.toDouble()).toInt().coerceIn(0, width)
            val b = ceil(mapped.maxOf { it.y }.toDouble()).toInt().coerceIn(0, height)
            val rect = Rect(l, t, r, b)
            if (rect.width() <= 0 || rect.height() <= 0) {
                Log.w(TAG, "skip OCR detection with empty bounds pass=${passName} index=${i} text=${text}")
                return@mapIndexedNotNull null
            }
            val matchedIndex = BubbleRegionMatcher.findBestContainer(l, t, r, b, bubbleBounds)
            val bubbleId = transform.bubbleId
                ?: if (matchedIndex >= 0) bubbleRegions[matchedIndex].id else null
            Line(i, item.confidence, rect, text, bubbleId)
        }
        val emptyResultCount = result.results.count {
            it.text.replace('\n', ' ').replace('\r', ' ').isBlank()
        }
        if (emptyResultCount > 0) {
            Log.w(TAG, "ignored empty OCR detections=${emptyResultCount}; valid_lines=${lines.size}; pass=${passName}")
        }
        lines.forEach { line ->
            Log.i(TAG, "line[${line.index}] pass=${passName} conf=${line.confidence} bubble=${line.bubbleId ?: "FREE_OR_UNMATCHED"} bounds=${line.bounds.flattenToString()} text=${line.text}")
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
            Log.i(TAG, "bubble_group id=${bubbleId} pass=${passName} lines=${ordered.size} text=${utterance}")
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
        Log.i(TAG, "grouping_summary pass=${passName} bubbles=${grouped.size} grouped_lines=${lines.size - freeCount} free_or_unmatched=${freeCount} output_groups=${utteranceGroups.size}")
        return utteranceGroups
    }
}
