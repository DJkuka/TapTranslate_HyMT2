package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.nio.FloatBuffer;

/** Synchronous inference output; the decoder consumes it before the next model invocation. */
public final class YoloInferenceOutput {
    private final FloatBuffer detections;
    private final int anchorCount;

    public YoloInferenceOutput(FloatBuffer detections, int anchorCount) {
        if (detections == null || anchorCount <= 0) {
            throw new IllegalArgumentException("Detection tensor and anchor count are required");
        }
        this.detections = detections.asReadOnlyBuffer();
        this.anchorCount = anchorCount;
    }

    public FloatBuffer getDetections() { return detections.asReadOnlyBuffer(); }
    public int getAnchorCount() { return anchorCount; }
}
