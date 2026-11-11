package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.util.Arrays;

/** One decoded dual-label YOLO detection in model-input pixel coordinates. */
public final class YoloDetection {
    private final OcrRegionKind kind;
    private final float left, top, right, bottom;
    private final float confidence;
    private final float[] maskCoefficients;

    YoloDetection(OcrRegionKind kind, float left, float top, float right, float bottom,
                  float confidence, float[] maskCoefficients) {
        this.kind = kind;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.confidence = confidence;
        this.maskCoefficients = Arrays.copyOf(maskCoefficients, maskCoefficients.length);
    }

    public OcrRegionKind getKind() { return kind; }
    public float getLeft() { return left; }
    public float getTop() { return top; }
    public float getRight() { return right; }
    public float getBottom() { return bottom; }
    public float getConfidence() { return confidence; }
    public float[] getMaskCoefficients() {
        return Arrays.copyOf(maskCoefficients, maskCoefficients.length);
    }
}
