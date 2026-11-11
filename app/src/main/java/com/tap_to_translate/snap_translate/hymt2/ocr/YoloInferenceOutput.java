package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.nio.FloatBuffer;

/** Synchronous inference output; the decoder consumes it before the next model invocation. */
public final class YoloInferenceOutput {
    private final FloatBuffer detections;
    private final int anchorCount;
    private final FloatBuffer maskPrototypes;
    private final int prototypeWidth;
    private final int prototypeHeight;

    public YoloInferenceOutput(FloatBuffer detections, int anchorCount) {
        this(detections, anchorCount, null, 0, 0);
    }

    public YoloInferenceOutput(
            FloatBuffer detections, int anchorCount,
            FloatBuffer maskPrototypes, int prototypeWidth, int prototypeHeight) {
        if (detections == null || anchorCount <= 0) {
            throw new IllegalArgumentException("Detection tensor and anchor count are required");
        }
        if ((maskPrototypes == null && (prototypeWidth != 0 || prototypeHeight != 0))
                || (maskPrototypes != null && (prototypeWidth <= 0 || prototypeHeight <= 0
                || maskPrototypes.limit() != prototypeWidth * prototypeHeight
                * YoloDualOutputDecoder.MASK_COEFFICIENT_COUNT))) {
            throw new IllegalArgumentException("Invalid YOLO mask prototype tensor");
        }
        this.detections = detections.asReadOnlyBuffer();
        this.anchorCount = anchorCount;
        this.maskPrototypes = maskPrototypes == null ? null : maskPrototypes.asReadOnlyBuffer();
        this.prototypeWidth = prototypeWidth;
        this.prototypeHeight = prototypeHeight;
    }

    public FloatBuffer getDetections() { return detections.asReadOnlyBuffer(); }
    public int getAnchorCount() { return anchorCount; }
    public FloatBuffer getMaskPrototypes() {
        return maskPrototypes == null ? null : maskPrototypes.asReadOnlyBuffer();
    }
    public int getPrototypeWidth() { return prototypeWidth; }
    public int getPrototypeHeight() { return prototypeHeight; }
}
