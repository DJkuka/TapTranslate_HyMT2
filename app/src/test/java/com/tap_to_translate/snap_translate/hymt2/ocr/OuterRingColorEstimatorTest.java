package com.tap_to_translate.snap_translate.hymt2.ocr;

import org.junit.Test;
import static org.junit.Assert.*;

public class OuterRingColorEstimatorTest {
    private static final int WIDTH = 120;
    private static final int HEIGHT = 100;

    @Test public void samplesFlatColourOutsideAndIgnoresRectangleInterior() {
        final int background = 0xffd8c4a8;
        BackgroundSample result = estimate((x, y) ->
                x >= 30 && x < 90 && y >= 30 && y < 70 ? 0xff111111 : background,
                30, 30, 90, 70, 2, 5);
        assertEquals(background, result.getColor());
        assertTrue(result.isReliable());
        assertTrue(result.getConfidence() > 0.95f);
    }

    @Test public void samplesNearRingRatherThanDistantPageColour() {
        final int near = 0xffe8e0c8;
        final int far = 0xff303030;
        BackgroundSample result = estimate((x, y) ->
                x >= 23 && x < 97 && y >= 23 && y < 77 ? near : far,
                30, 30, 90, 70, 2, 5);
        assertEquals(near, result.getColor());
        assertTrue(result.isReliable());
    }

    @Test public void reportsUnreliableWhenRingIsStronglyMixed() {
        BackgroundSample result = estimate((x, y) -> ((x + y) & 1) == 0
                ? 0xffeeeeee : 0xff202020, 30, 30, 90, 70, 2, 5);
        assertFalse(result.isReliable());
    }

    @Test public void clipsRingAtBitmapEdges() {
        BackgroundSample result = estimate((x, y) -> 0xffb0b0b0,
                0, 0, 25, 20, 2, 5);
        assertEquals(0xffb0b0b0, result.getColor());
        assertTrue(result.isReliable());
    }

    @Test public void noAvailableOuterRingReturnsUnreliableFallback() {
        BackgroundSample result = estimate((x, y) -> 0xff000000,
                0, 0, WIDTH, HEIGHT, 2, 5);
        assertFalse(result.isReliable());
        assertEquals(0, result.getSampleCount());
    }

    private static BackgroundSample estimate(
            Pixel pixel, int left, int top, int right, int bottom, int gap, int thickness) {
        return OuterRingColorEstimator.estimate(WIDTH, HEIGHT, left, top, right, bottom,
                gap, thickness, pixel::get);
    }

    private interface Pixel { int get(int x, int y); }
}
