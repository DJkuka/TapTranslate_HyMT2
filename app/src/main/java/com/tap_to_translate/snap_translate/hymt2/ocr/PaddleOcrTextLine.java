package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Rect;

/** One Paddle-recognized text line with its own bitmap-local geometry. */
public final class PaddleOcrTextLine {
    private final String text;
    private final Rect bounds;
    private final float confidence;

    public PaddleOcrTextLine(String text, Rect bounds, float confidence) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text is empty");
        }
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            throw new IllegalArgumentException("Bounds must be non-empty");
        }
        if (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("Confidence must be in [0, 1]");
        }
        this.text = text;
        this.bounds = new Rect(bounds);
        this.confidence = confidence;
    }

    public String getText() { return text; }
    public Rect getBounds() { return new Rect(bounds); }
    public float getConfidence() { return confidence; }
}
