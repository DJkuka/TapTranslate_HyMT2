package com.tap_to_translate.snap_translate.hymt2.ocr;

/** Estimated background colour sampled outside a text replacement rectangle. */
public final class BackgroundSample {
    private final int color;
    private final int sampleCount;
    private final float confidence;
    private final boolean reliable;

    BackgroundSample(int color, int sampleCount, float confidence, boolean reliable) {
        this.color = color;
        this.sampleCount = sampleCount;
        this.confidence = confidence;
        this.reliable = reliable;
    }

    /** Opaque ARGB colour. */
    public int getColor() { return color; }
    public int getSampleCount() { return sampleCount; }
    /** Fraction of ring samples belonging to the dominant neighbouring colour cluster. */
    public float getConfidence() { return confidence; }
    public boolean isReliable() { return reliable; }
}
