package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Rect;

/** Raw OCR-engine output. Its rectangle is in bitmap-local pixels. */
public final class RecognizedText {
    private final String text;
    private final Rect bounds;
    private final float confidence;
    private final String engineId;

    public RecognizedText(String text, Rect bounds, float confidence, String engineId) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Text is empty");
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            throw new IllegalArgumentException("Text bounds must be non-empty");
        }
        if (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("Confidence must be in [0, 1]");
        }
        if (engineId == null || engineId.trim().isEmpty()) {
            throw new IllegalArgumentException("Engine id is required");
        }
        this.text = text;
        this.bounds = new Rect(bounds);
        this.confidence = confidence;
        this.engineId = engineId;
    }

    public String getText() { return text; }
    public Rect getBounds() { return new Rect(bounds); }
    public float getConfidence() { return confidence; }
    public String getEngineId() { return engineId; }
}
