package com.tap_to_translate.snap_translate.hymt2.ocr;

/**
 * Applies a binary/grayscale BubblePop alpha mask to a rectangular pixel crop.
 * Kept independent of Android Bitmap so the clipping math can be unit-tested.
 */
public final class BubbleOverlayMasker {
    private BubbleOverlayMasker() {}

    public static int applyMask(
            int[] pixels, int width, int height,
            int pixelOriginX, int pixelOriginY,
            int maskLeft, int maskTop,
            byte[] mask, int maskWidth, int maskHeight) {
        if (pixels == null || mask == null || width <= 0 || height <= 0
                || maskWidth <= 0 || maskHeight <= 0
                || pixels.length != width * height
                || mask.length != maskWidth * maskHeight) return 0;

        int changed = 0;
        for (int y = 0; y < height; y++) {
            int maskY = pixelOriginY + y - maskTop;
            if (maskY < 0 || maskY >= maskHeight) continue;
            int pixelRow = y * width;
            int maskRow = maskY * maskWidth;
            for (int x = 0; x < width; x++) {
                int maskX = pixelOriginX + x - maskLeft;
                if (maskX < 0 || maskX >= maskWidth) continue;
                int index = pixelRow + x;
                int color = pixels[index];
                int alpha = (color >>> 24) & 0xff;
                int maskAlpha = mask[maskRow + maskX] & 0xff;
                int resultAlpha = (alpha * maskAlpha + 127) / 255;
                if (resultAlpha != alpha) {
                    pixels[index] = (color & 0x00ffffff) | (resultAlpha << 24);
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Shrinks a grayscale alpha mask inward by radius pixels using a minimum filter.
     * The returned mask is separate; OCR and glyph placement retain the original.
     */
    public static byte[] erodeMask(byte[] mask, int width, int height, int radius) {
        if (mask == null || width <= 0 || height <= 0
                || mask.length != width * height) return new byte[0];
        if (radius <= 0) return mask.clone();
        byte[] result = new byte[mask.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int minimum = 255;
                for (int dy = -radius; dy <= radius && minimum > 0; dy++) {
                    int sampleY = y + dy;
                    if (sampleY < 0 || sampleY >= height) {
                        minimum = 0;
                        break;
                    }
                    int row = sampleY * width;
                    for (int dx = -radius; dx <= radius; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= width) {
                            minimum = 0;
                            break;
                        }
                        minimum = Math.min(minimum, mask[row + sampleX] & 0xff);
                        if (minimum == 0) break;
                    }
                }
                result[y * width + x] = (byte) minimum;
            }
        }
        return result;
    }
}
