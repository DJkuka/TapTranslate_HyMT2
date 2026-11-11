package com.tap_to_translate.snap_translate.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

/**
 * A captured image plus an explicit mapping from bitmap-local pixels to full-screen pixels.
 *
 * The bitmap may be the whole screen or a crop. All detector/recognizer coordinates are
 * bitmap-local. Conversion to full-screen coordinates happens only through this class.
 */
public final class OcrFrame {
    private final Bitmap bitmap;
    private final Rect visibleBounds;
    private final int screenOriginX;
    private final int screenOriginY;
    private final float screenScaleX;
    private final float screenScaleY;

    public OcrFrame(
            Bitmap bitmap,
            Rect visibleBounds,
            int screenOriginX,
            int screenOriginY,
            float screenScaleX,
            float screenScaleY) {
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("OCR frame bitmap is null or recycled");
        }
        if (visibleBounds == null) {
            throw new IllegalArgumentException("visibleBounds is required");
        }
        if (!Float.isFinite(screenScaleX) || !Float.isFinite(screenScaleY)
                || screenScaleX <= 0f || screenScaleY <= 0f) {
            throw new IllegalArgumentException("Screen scales must be finite and positive");
        }

        Rect safeBounds = new Rect(visibleBounds);
        if (!safeBounds.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) {
            throw new IllegalArgumentException("visibleBounds is outside the bitmap");
        }

        this.bitmap = bitmap;
        this.visibleBounds = safeBounds;
        this.screenOriginX = screenOriginX;
        this.screenOriginY = screenOriginY;
        this.screenScaleX = screenScaleX;
        this.screenScaleY = screenScaleY;
    }

    /** Full-frame helper for an unscaled screen capture without a crop offset. */
    public static OcrFrame fullScreen(Bitmap bitmap, int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException("Screen dimensions must be positive");
        }
        return new OcrFrame(
                bitmap,
                new Rect(0, 0, Math.min(screenWidth, bitmap.getWidth()),
                        Math.min(screenHeight, bitmap.getHeight())),
                0, 0, 1f, 1f);
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public Rect getVisibleBounds() {
        return new Rect(visibleBounds);
    }

    public Rect clampToVisibleBounds(Rect localBounds) {
        if (localBounds == null) return null;
        Rect result = new Rect(localBounds);
        if (!result.intersect(visibleBounds)) return null;
        if (result.width() <= 0 || result.height() <= 0) return null;
        return result;
    }

    /** Maps a bitmap-local rectangle to full-screen physical pixel coordinates. */
    public Rect toScreenBounds(Rect localBounds) {
        Rect clipped = clampToVisibleBounds(localBounds);
        if (clipped == null) return null;
        int left = screenOriginX + Math.round(clipped.left * screenScaleX);
        int top = screenOriginY + Math.round(clipped.top * screenScaleY);
        int right = screenOriginX + Math.round(clipped.right * screenScaleX);
        int bottom = screenOriginY + Math.round(clipped.bottom * screenScaleY);
        if (right <= left || bottom <= top) return null;
        return new Rect(left, top, right, bottom);
    }
}
