package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.util.List;

/**
 * Associates OCR rectangles with class-0 bubble containers.
 * Class-1 text boxes are intentionally not used as bubble identities:
 * one bubble can contain several text boxes or OCR chunks.
 */
public final class BubbleRegionMatcher {
    private BubbleRegionMatcher() {}

    public static int findBestContainer(
            int left, int top, int right, int bottom, List<Bounds> bubbles) {
        if (right <= left || bottom <= top || bubbles == null || bubbles.isEmpty()) return -1;
        int centerX = left + (right - left) / 2;
        int centerY = top + (bottom - top) / 2;
        long textArea = (long) (right - left) * (bottom - top);
        int best = -1;
        long bestArea = Long.MAX_VALUE;
        double bestOverlap = 0d;

        for (int i = 0; i < bubbles.size(); i++) {
            Bounds bubble = bubbles.get(i);
            if (bubble == null || bubble.right <= bubble.left || bubble.bottom <= bubble.top) continue;
            int il = Math.max(left, bubble.left);
            int it = Math.max(top, bubble.top);
            int ir = Math.min(right, bubble.right);
            int ib = Math.min(bottom, bubble.bottom);
            long intersection = ir > il && ib > it
                    ? (long) (ir - il) * (ib - it) : 0L;
            boolean centerInside = centerX >= bubble.left && centerX < bubble.right
                    && centerY >= bubble.top && centerY < bubble.bottom;
            double coverage = (double) intersection / (double) textArea;
            if (!centerInside && coverage < 0.60d) continue;

            long area = (long) (bubble.right - bubble.left) * (bubble.bottom - bubble.top);
            double score = centerInside ? Math.max(coverage, 0.50d) : coverage;
            if (score > bestOverlap + 0.0001d
                    || (Math.abs(score - bestOverlap) <= 0.0001d && area < bestArea)) {
                best = i;
                bestArea = area;
                bestOverlap = score;
            }
        }
        return best;
    }

    /**
     * Host paragraph grouping may join OCR chunks only when both belong to the
     * same speech bubble, or both are outside all detected bubble containers.
     */
    public static boolean canGroupSameRegion(int firstContainer, int secondContainer) {
        return firstContainer == secondContainer;
    }

    public static final class Bounds {
        public final int left, top, right, bottom;
        public Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
