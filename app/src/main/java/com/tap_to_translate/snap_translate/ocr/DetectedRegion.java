package com.tap_to_translate.snap_translate.ocr;

import android.graphics.Rect;
import java.util.Arrays;

/** Detector output. Bounds and optional contour coordinates are bitmap-local pixels. */
public final class DetectedRegion {
    private final String id;
    private final OcrRegionKind kind;
    private final Rect bounds;
    private final float confidence;
    private final float[] contour;

    public DetectedRegion(
            String id, OcrRegionKind kind, Rect bounds, float confidence, float[] contour) {
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
        this.id = id;
        this.kind = kind;
        this.bounds = new Rect(bounds);
        this.confidence = confidence;
        this.contour = contour == null ? null : Arrays.copyOf(contour, contour.length);
    }

    public String getId() { return id; }
    public OcrRegionKind getKind() { return kind; }
    public Rect getBounds() { return new Rect(bounds); }
    public float getConfidence() { return confidence; }
    public float[] getContour() {
        return contour == null ? null : Arrays.copyOf(contour, contour.length);
    }
}
