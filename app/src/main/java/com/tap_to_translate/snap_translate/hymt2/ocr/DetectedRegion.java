package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Rect;
import java.util.Arrays;

/** Detector output. Bounds and optional contour coordinates are bitmap-local pixels. */
public final class DetectedRegion {
    private final String id;
    private final OcrRegionKind kind;
    private final Rect bounds;
    private final float confidence;
    private final float[] contour;
    private final float[] maskCoefficients;
    /** Cropped CV_8UC1 mask pixels; dimensions match bounds. */
    private final byte[] maskData;
    private final int maskWidth;
    private final int maskHeight;

    public DetectedRegion(
            String id, OcrRegionKind kind, Rect bounds, float confidence, float[] contour) {
        this(id, kind, bounds, confidence, contour, null, null, 0, 0);
    }

    public DetectedRegion(
            String id, OcrRegionKind kind, Rect bounds, float confidence,
            float[] contour, float[] maskCoefficients) {
        this(id, kind, bounds, confidence, contour, maskCoefficients, null, 0, 0);
    }

    public DetectedRegion(
            String id, OcrRegionKind kind, Rect bounds, float confidence,
            float[] contour, float[] maskCoefficients,
            byte[] maskData, int maskWidth, int maskHeight) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Region id is required");
        if (kind == null) throw new IllegalArgumentException("Region kind is required");
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            throw new IllegalArgumentException("Region bounds must be non-empty");
        }
        if (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("Confidence must be in [0, 1]");
        }
        if (contour != null && (contour.length < 6 || contour.length % 2 != 0)) {
            throw new IllegalArgumentException("Contour must contain at least three x/y pairs");
        }
        if (maskCoefficients != null && maskCoefficients.length != YoloDualOutputDecoder.MASK_COEFFICIENT_COUNT) {
            throw new IllegalArgumentException("Unexpected YOLO mask coefficient count");
        }
        this.id = id;
        this.kind = kind;
        this.bounds = new Rect(bounds);
        this.confidence = confidence;
        this.contour = contour == null ? null : Arrays.copyOf(contour, contour.length);
        if (maskData != null && (maskWidth != bounds.width() || maskHeight != bounds.height()
                || maskData.length != maskWidth * maskHeight)) {
            throw new IllegalArgumentException("Mask dimensions must match region bounds");
        }
        this.maskCoefficients = maskCoefficients == null ? null
                : Arrays.copyOf(maskCoefficients, maskCoefficients.length);
        this.maskData = maskData == null ? null : Arrays.copyOf(maskData, maskData.length);
        this.maskWidth = maskData == null ? 0 : maskWidth;
        this.maskHeight = maskData == null ? 0 : maskHeight;
    }

    public String getId() { return id; }
    public OcrRegionKind getKind() { return kind; }
    public Rect getBounds() { return new Rect(bounds); }
    public float getConfidence() { return confidence; }
    public float[] getContour() {
        return contour == null ? null : Arrays.copyOf(contour, contour.length);
    }
    public float[] getMaskCoefficients() {
        return maskCoefficients == null ? null : Arrays.copyOf(maskCoefficients, maskCoefficients.length);
    }
    public byte[] getMaskData() {
        return maskData == null ? null : Arrays.copyOf(maskData, maskData.length);
    }
    /** Package-private read-only view for the live renderer; callers must not modify it. */
    byte[] maskDataView() { return maskData; }
    public int getMaskWidth() { return maskWidth; }
    public int getMaskHeight() { return maskHeight; }
}