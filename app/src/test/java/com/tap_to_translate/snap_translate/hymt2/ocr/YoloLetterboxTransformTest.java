package com.tap_to_translate.snap_translate.hymt2.ocr;

import org.junit.Test;
import static org.junit.Assert.*;

public class YoloLetterboxTransformTest {
    @Test public void mapsWideScreenBoxBackFromVerticalPadding() {
        YoloLetterboxTransform transform = new YoloLetterboxTransform(
                2000, 1200, 1472, 1472, 1472, 883, 0f, 294.5f);
        assertEquals(1000f, transform.toSourceX(736f), 0.01f);
        assertEquals(600f, transform.toSourceY(294.5f + 441.5f), 0.02f);
    }

    @Test public void mapsTallScreenBoxBackFromHorizontalPadding() {
        YoloLetterboxTransform transform = new YoloLetterboxTransform(
                1200, 2000, 1472, 1472, 883, 1472, 294.5f, 0f);
        assertEquals(600f, transform.toSourceX(294.5f + 441.5f), 0.02f);
        assertEquals(1000f, transform.toSourceY(736f), 0.01f);
    }

    @Test public void preservesNonSquareResizeScales() {
        YoloLetterboxTransform transform = new YoloLetterboxTransform(
                2000, 1200, 1472, 1472, 1472, 883, 0f, 294.5f);
        assertEquals(1472f / 2000f, transform.getScaleX(), 0.0001f);
        assertEquals(883f / 1200f, transform.getScaleY(), 0.0001f);
    }
}
