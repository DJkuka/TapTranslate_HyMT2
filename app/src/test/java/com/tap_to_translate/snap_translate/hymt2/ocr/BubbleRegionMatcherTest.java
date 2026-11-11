package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class BubbleRegionMatcherTest {
    @Test public void assignsTextToContainingBubble() {
        assertEquals(0, BubbleRegionMatcher.findBestContainer(
                20, 20, 60, 40,
                Arrays.asList(new BubbleRegionMatcher.Bounds(0, 0, 100, 80))));
    }

    @Test public void choosesCorrectBubbleForSideBySideBalloons() {
        assertEquals(1, BubbleRegionMatcher.findBestContainer(
                120, 20, 160, 40,
                Arrays.asList(
                        new BubbleRegionMatcher.Bounds(0, 0, 100, 80),
                        new BubbleRegionMatcher.Bounds(105, 0, 205, 80))));
    }

    @Test public void multipleOcrChunksInsideSameBubbleShareContainer() {
        BubbleRegionMatcher.Bounds bubble = new BubbleRegionMatcher.Bounds(0, 0, 200, 120);
        assertEquals(0, BubbleRegionMatcher.findBestContainer(
                20, 20, 80, 45, Arrays.asList(bubble)));
        assertEquals(0, BubbleRegionMatcher.findBestContainer(
                20, 50, 90, 75, Arrays.asList(bubble)));
    }

    @Test public void leavesUnmatchedTextUnassignedInsteadOfGuessing() {
        assertEquals(-1, BubbleRegionMatcher.findBestContainer(
                220, 220, 260, 250,
                Arrays.asList(new BubbleRegionMatcher.Bounds(0, 0, 100, 80))));
    }
}
