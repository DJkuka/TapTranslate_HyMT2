package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Rect;

/** One utterance prepared for the host translation pipeline. */
public final class PaddleOcrTextGroup {
    private final String text;
    private final Rect bounds;
    private final String bubbleId;
    private final float confidence;
    private final int lineCount;

    public PaddleOcrTextGroup(String text, Rect bounds, String bubbleId,
            float confidence, int lineCount) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Text is empty");
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            throw new IllegalArgumentException("Bounds must be non-empty");
        }
        if (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("Confidence must be in [0, 1]");
        }
        if (lineCount < 1) throw new IllegalArgumentException("lineCount must be positive");
        this.text = text;
        this.bounds = new Rect(bounds);
        this.bubbleId = bubbleId;
        this.confidence = confidence;
        this.lineCount = lineCount;
    }

    public String getText() { return text; }
    public Rect getBounds() { return new Rect(bounds); }
    public String getBubbleId() { return bubbleId; }
    public float getConfidence() { return confidence; }
    public int getLineCount() { return lineCount; }
}