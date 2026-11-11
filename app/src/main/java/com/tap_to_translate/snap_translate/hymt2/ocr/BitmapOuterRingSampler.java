package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

/** Android adapter for sampling an outer ring from the original captured bitmap. */
public final class BitmapOuterRingSampler {
    private BitmapOuterRingSampler() {}

    public static BackgroundSample sample(
            final Bitmap bitmap, Rect replacementBounds, int gapPx, int thicknessPx) {
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("Source bitmap is null or recycled");
        }
        if (replacementBounds == null || replacementBounds.isEmpty()) {
            throw new IllegalArgumentException("Replacement bounds are empty");
        }
        final Rect safe = new Rect(replacementBounds);
        return OuterRingColorEstimator.estimate(
                bitmap.getWidth(), bitmap.getHeight(),
                safe.left, safe.top, safe.right, safe.bottom,
                Math.max(0, gapPx), Math.max(1, thicknessPx),
                new OuterRingColorEstimator.PixelReader() {
                    @Override public int getPixel(int x, int y) {
                        return bitmap.getPixel(x, y);
                    }
                });
    }
}
