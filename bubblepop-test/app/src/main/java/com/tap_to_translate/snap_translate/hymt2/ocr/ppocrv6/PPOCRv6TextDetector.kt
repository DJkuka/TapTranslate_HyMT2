package com.tap_to_translate.snap_translate.hymt2.ocr.ppocrv6

import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.engine.DetectionEngine
import com.paddle.ocr.engine.ORTSessionManager
import org.opencv.core.Mat

/** PP-OCRv6 tiny text detector. Recognition is omitted; only text-line geometry is needed. */
class PPOCRv6TextDetector(context: Context) : AutoCloseable {
    private val manager = ORTSessionManager(context.applicationContext, EngineConfig(numThreads = 4))
    private val engine: DetectionEngine
    val coldLoadTimeMs: Long get() = manager.coldLoadTimeMs

    init {
        try {
            manager.loadDetectionModel("models/ppocrv6_tiny_det/inference.onnx")
            engine = DetectionEngine(manager, PaddleOCRConfig(
                detImgMode = "BGR", detLimitSideLen = 960, detLimitType = "max",
                detMaxSideLimit = 4000, detThresh = 0.2f, detBoxThresh = 0.4f,
                detUnclipRatio = 1.4f, detMaxCandidates = 3000,
                detUseDilation = false, detScoreMode = "fast", detBoxType = "quad"
            ))
        } catch (t: Throwable) {
            manager.release()
            throw t
        }
    }

    fun detect(bgr: Mat): DetectionEngine.DetectionResult {
        check(!bgr.empty()) { "PP-OCRv6 input image is empty" }
        return engine.detect(bgr)
    }

    override fun close() = manager.release()
}
