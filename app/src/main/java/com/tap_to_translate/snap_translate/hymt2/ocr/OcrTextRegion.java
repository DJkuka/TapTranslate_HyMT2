package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Rect;

/** Normalized output, independent of the OCR engine used to produce it. */
public final class OcrTextRegion {
    private final String id;
    private final String parentRegionId;
    private final OcrRegionKind kind;
    private final String text;
    private final Rect frameBounds;
    private final Rect screenBounds;
    private final float confidence;
    private final String engineId;

    OcrTextRegion(
            String id,
            String parentRegionId,
            OcrRegionKind kind,
            String text,
            Rect frameBounds,
            Rect screenBounds,
            float confidence,
            String engineId) {
        this.id = id;
        this.parentRegionId = parentRegionId;
        this.kind = kind;
        this.text = text;
        this.frameBounds = new Rect(frameBounds);
        this.screenBounds = new Rect(screenBounds);
        this.confidence = confidence;
        this.engineId = engineId;
    }

    public String getId() { return id; }
    public String getParentRegionId() { return parentRegionId; }
    public OcrRegionKind getKind() { return kind; }
    public String getText() { return text; }
    /** Bitmap-local rectangle; used for colour sampling from the original image. */
    public Rect getFrameBounds() { return new Rect(frameBounds); }
    /** Full-screen rectangle; used when creating the existing host overlay model. */
    public Rect getScreenBounds() { return new Rect(screenBounds); }
    public float getConfidence() { return confidence; }
    public String getEngineId() { return engineId; }
}
