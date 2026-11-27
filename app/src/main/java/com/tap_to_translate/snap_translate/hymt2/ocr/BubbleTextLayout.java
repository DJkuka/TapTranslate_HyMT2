package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.nio.ByteBuffer;

/**
 * Mask-aware text layout over a cropped BubblePop instance mask.
 *
 * Lines are fitted to the mask while they overlap the balloon. Pixels outside the
 * mask are unrestricted, allowing the translated text block to continue beyond
 * the balloon instead of being pinned to, or clipped by, its contour.
 */
public final class BubbleTextLayout {
    private static final String TAG = "TapTranslateBubbleLayout";
    private static final Map<DetectedRegion, Bitmap> MASK_BITMAPS =
            java.util.Collections.synchronizedMap(new WeakHashMap<DetectedRegion, Bitmap>());
    private static final Map<DetectedRegion, Bitmap> INNER_BACKGROUND_MASK_BITMAPS =
            java.util.Collections.synchronizedMap(new WeakHashMap<DetectedRegion, Bitmap>());
    private BubbleTextLayout() {}

    public static boolean draw(Canvas canvas, byte[] mask, int maskWidth, int maskHeight,
            Rect maskBounds, Rect overlayBounds, CharSequence source, TextPaint paint,
            float minTextSize, float maxTextSize, int padding) {
        return draw(canvas, mask, maskWidth, maskHeight, maskBounds, overlayBounds,
                source, paint, minTextSize, maxTextSize, padding, true, 0f, 0f, 1f);
    }

    public static boolean draw(Canvas canvas, byte[] mask, int maskWidth, int maskHeight,
            Rect maskBounds, Rect overlayBounds, CharSequence source, TextPaint paint,
            float minTextSize, float maxTextSize, int padding, boolean autoWrap,
            float offsetX, float offsetY, float fontScale) {
        return draw(canvas, mask, maskWidth, maskHeight, maskBounds, overlayBounds,
                source, paint, minTextSize, maxTextSize, padding, autoWrap,
                offsetX, offsetY, fontScale, 1.0f);
    }

    public static boolean draw(Canvas canvas, byte[] mask, int maskWidth, int maskHeight,
            Rect maskBounds, Rect overlayBounds, CharSequence source, TextPaint paint,
            float minTextSize, float maxTextSize, int padding, boolean autoWrap,
            float offsetX, float offsetY, float fontScale, float lineSpacing) {
        return draw(canvas, mask, maskWidth, maskHeight, maskBounds, overlayBounds,
                source, paint, minTextSize, maxTextSize, padding, autoWrap,
                offsetX, offsetY, fontScale, lineSpacing, false);
    }

    /** BubblePop may let the text block continue past the detected balloon contour. */
    public static boolean draw(Canvas canvas, byte[] mask, int maskWidth, int maskHeight,
            Rect maskBounds, Rect overlayBounds, CharSequence source, TextPaint paint,
            float minTextSize, float maxTextSize, int padding, boolean autoWrap,
            float offsetX, float offsetY, float fontScale, float lineSpacing,
            boolean allowOverflow) {
        if (canvas == null || mask == null || maskWidth <= 0 || maskHeight <= 0
                || mask.length != maskWidth * maskHeight || maskBounds == null
                || maskBounds.width() != maskWidth || maskBounds.height() != maskHeight
                || overlayBounds == null || source == null || paint == null
                || source.length() == 0) return false;

        // Use the full local balloon mask as the layout area. In ordinary OCR
        // mode keep the complete text block inside that contour, as before.
        // BubblePop may opt into continuing the translated block beyond the contour.
        Rect area = new Rect(maskBounds);
        int verticalInset = 2;
        int horizontalPadding = Math.max(2, padding);
        area.inset(verticalInset, verticalInset);
        area.offset(Math.round(offsetX), Math.round(offsetY));
        if (!allowOverflow) {
            if (area.left < maskBounds.left) area.offset(maskBounds.left - area.left, 0);
            if (area.top < maskBounds.top) area.offset(0, maskBounds.top - area.top);
            if (area.right > maskBounds.right) area.offset(maskBounds.right - area.right, 0);
            if (area.bottom > maskBounds.bottom) area.offset(0, maskBounds.bottom - area.bottom);
        }
        if (area.width() < 4 || area.height() < 4) return false;

        float originalSize = paint.getTextSize();
        float safeScale = Math.max(0.25f, Math.min(5.00f, fontScale));
        // Fit the natural text size first. Apply the user's scale to that real
        // fitted size, not to maxTextSize (which made large maxima appear stuck).
        float upper = Math.max(minTextSize, maxTextSize);
        float lower = Math.max(4.0f, Math.min(minTextSize, upper));
        try {
            String layoutText = source.toString();
            if (!autoWrap) layoutText = layoutText.replace('\n', ' ').replace('\r', ' ');
            float baseSize = 0f;
            List<Line> baseLines = null;
            boolean wrapFallback = false;
            for (float size = upper; size >= lower - 0.01f; size -= 0.5f) {
                paint.setTextSize(size);
                List<Line> candidate = layout(mask, maskWidth, maskHeight, maskBounds,
                        area, layoutText, paint, horizontalPadding, autoWrap, lineSpacing, allowOverflow);
                if (candidate != null && !candidate.isEmpty()) {
                    baseSize = size; baseLines = candidate; break;
                }
            }
            if (baseLines == null && !autoWrap) {
                for (float size = upper; size >= lower - 0.01f; size -= 0.5f) {
                    paint.setTextSize(size);
                    List<Line> candidate = layout(mask, maskWidth, maskHeight, maskBounds,
                            area, source.toString(), paint, horizontalPadding, true, lineSpacing, allowOverflow);
                    if (candidate != null && !candidate.isEmpty()) {
                        baseSize = size; baseLines = candidate; wrapFallback = true; break;
                    }
                }
            }
            if (baseLines == null) return false;
            float wantedSize = Math.max(lower, baseSize * safeScale);
            boolean wrap = wrapFallback || autoWrap;
            paint.setTextSize(wantedSize);
            List<Line> finalLines = layout(mask, maskWidth, maskHeight, maskBounds,
                    area, source.toString(), paint, horizontalPadding, wrap, lineSpacing, allowOverflow);
            float low = lower, high = wantedSize;
            if (finalLines == null || finalLines.isEmpty()) {
                if (wantedSize >= baseSize) {
                    low = baseSize;
                    finalLines = baseLines;
                } else {
                    high = wantedSize;
                }
                for (int i = 0; i < 16; i++) {
                    float mid = (low + high) * 0.5f;
                    paint.setTextSize(mid);
                    List<Line> candidate = layout(mask, maskWidth, maskHeight, maskBounds,
                            area, source.toString(), paint, horizontalPadding, wrap, lineSpacing, allowOverflow);
                    if (candidate != null && !candidate.isEmpty()) {
                        low = mid; finalLines = candidate;
                    } else high = mid;
                }
                paint.setTextSize(low);
            }
            if (finalLines == null || finalLines.isEmpty()) return false;
            float finalSize = paint.getTextSize();
            android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
            float usedTop = finalLines.get(0).baseline + fm.ascent;
            float usedBottom = finalLines.get(finalLines.size() - 1).baseline + fm.descent;
            float verticalShift = (area.top + area.bottom - usedTop - usedBottom) / 2f;
            android.graphics.Paint.Align oldAlign = paint.getTextAlign();
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            for (Line line : finalLines) canvas.drawText(line.text, line.x,
                    line.baseline + verticalShift, paint);
            paint.setTextAlign(oldAlign);
            Log.i(TAG, "shape_layout=fit lines=" + finalLines.size()
                    + " font=" + finalSize + " base=" + baseSize + " scale=" + safeScale);
            return true;
        } finally {
            paint.setTextSize(originalSize);
        }
    }

    private static List<Line> layout(byte[] mask, int width, int height, Rect maskBounds,
            Rect area, String text, TextPaint paint, int padding, boolean autoWrap,
            float lineSpacing, boolean allowOverflow) {
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        int glyphHeight = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent + fm.leading));
        float safeLineSpacing = Math.max(0.85f, Math.min(3.0f, lineSpacing));
        int lineAdvance = Math.max(1, Math.round(glyphHeight * safeLineSpacing));
        int scanStep = Math.max(1, glyphHeight / 4);
        int y = area.top;
        int offset = 0;
        ArrayList<Line> result = new ArrayList<>();

        while (offset < text.length()) {
            if (text.charAt(offset) == '\n') {
                offset++;
                y += lineAdvance;
                continue;
            }
            while (offset < text.length() && Character.isWhitespace(text.charAt(offset))
                    && text.charAt(offset) != '\n') offset++;
            if (offset >= text.length()) break;
            if (y + glyphHeight > area.bottom) return null;

            int paragraphEnd = text.indexOf('\n', offset);
            if (paragraphEnd < 0) paragraphEnd = text.length();

            // Build lines from complete words only. Never let breakText() split the
            // first word (or any later word) at an arbitrary character boundary.
            boolean placed = false;
            while (!placed && y + glyphHeight <= area.bottom) {
                int[] run = widestCommonRun(mask, width, height, maskBounds, area,
                        y, glyphHeight, padding, allowOverflow);
                if (run == null) {
                    y += scanStep;
                    continue;
                }
                float available = run[1] - run[0];
                int firstWordEnd = wordEnd(text, offset, paragraphEnd);
                if (firstWordEnd <= offset) {
                    offset = paragraphEnd;
                    break;
                }
                String firstWord = text.substring(offset, firstWordEnd);
                // A word that is too wide for this row is retried lower in the
                // balloon, where an irregular mask may provide a wider interval.
                if (paint.measureText(firstWord) > available + 0.5f) {
                    y += scanStep;
                    continue;
                }

                StringBuilder line = new StringBuilder(firstWord);
                int cursor = firstWordEnd;
                while (cursor < paragraphEnd) {
                    int nextStart = cursor;
                    while (nextStart < paragraphEnd
                            && Character.isWhitespace(text.charAt(nextStart))) nextStart++;
                    if (nextStart >= paragraphEnd) {
                        cursor = paragraphEnd;
                        break;
                    }
                    int nextEnd = wordEnd(text, nextStart, paragraphEnd);
                    if (nextEnd <= nextStart) break;
                    String candidate = line.toString() + " " + text.substring(nextStart, nextEnd);
                    if (paint.measureText(candidate) > available + 0.5f) break;
                    line.append(' ').append(text, nextStart, nextEnd);
                    cursor = nextEnd;
                }

                if (!autoWrap && cursor < paragraphEnd) return null;
                String lineText = line.toString();
                float textWidth = paint.measureText(lineText);
                if (textWidth > available + 0.5f) return null;
                float x = run[0] + (available - textWidth) / 2f;
                result.add(new Line(lineText, x, y - fm.ascent));

                offset = cursor;
                while (offset < paragraphEnd && Character.isWhitespace(text.charAt(offset))) offset++;
                if (offset >= paragraphEnd) {
                    offset = paragraphEnd;
                    if (offset < text.length() && text.charAt(offset) == '\n') offset++;
                }
                y += lineAdvance;
                placed = true;
            }
            if (!placed && y + glyphHeight > area.bottom) return null;
        }
        return result;
    }

    private static int wordEnd(String text, int start, int limit) {
        int end = start;
        while (end < limit && !Character.isWhitespace(text.charAt(end))) end++;
        return end;
    }

    /** Longest x interval whose pixels are all inside the mask for every row of the glyph band. */
    private static int[] widestCommonRun(byte[] mask, int width, int height, Rect maskBounds,
            Rect area, int top, int lineHeight, int padding, boolean allowOverflow) {
        int y0 = top;
        int y1 = top + lineHeight;
        if (y1 <= y0 || area.right <= area.left) return null;
        int x0 = allowOverflow ? area.left : Math.max(area.left, maskBounds.left);
        int x1 = allowOverflow ? area.right : Math.min(area.right, maskBounds.right);
        if (!allowOverflow) {
            y0 = Math.max(area.top, y0);
            y1 = Math.min(Math.min(area.bottom, y1), maskBounds.bottom);
        }
        int bestStart = -1, bestEnd = -1, runStart = -1;
        for (int x = x0; x < x1; x++) {
            boolean inside = true;
            // In ordinary mode every pixel of the glyph band must remain inside
            // the contour. BubblePop can relax only the part outside the mask.
            if (!allowOverflow && (y0 < maskBounds.top || y1 > maskBounds.bottom)) {
                inside = false;
            }
            if (inside) {
                for (int y = y0; y < y1; y++) {
                    if (allowOverflow && (y < maskBounds.top || y >= maskBounds.bottom)) continue;
                    int ix = x - maskBounds.left;
                    int iy = y - maskBounds.top;
                    if (ix < 0 || ix >= width || iy < 0 || iy >= height
                            || mask[iy * width + ix] == 0) {
                        inside = false;
                        break;
                    }
                }
            }
            if (inside) {
                if (runStart < 0) runStart = x;
            } else if (runStart >= 0) {
                if (bestStart < 0 || x - runStart > bestEnd - bestStart) {
                    bestStart = runStart;
                    bestEnd = x;
                }
                runStart = -1;
            }
        }
        if (runStart >= 0 && (bestStart < 0 || x1 - runStart > bestEnd - bestStart)) {
            bestStart = runStart;
            bestEnd = x1;
        }
        bestStart += padding;
        bestEnd -= padding;
        return bestEnd > bestStart ? new int[]{bestStart, bestEnd} : null;
    }

    /**
     * Draws a rounded translation fill, clipped by the exact BubblePop mask.
     * A cached ALPHA_8 bitmap makes the clipping independent of the overlay's
     * rectangular bounds while preserving the configured fill alpha.
     */
    public static boolean drawClippedBackground(Canvas canvas, DetectedRegion region,
            float left, float top, float right, float bottom,
            float radiusX, float radiusY, Paint fillPaint) {
        if (canvas == null || region == null || fillPaint == null
                || right <= left || bottom <= top) return false;
        // Inset only the white fill. Text layout continues to use the original mask.
        Bitmap maskBitmap = getInnerBackgroundMaskBitmap(region);
        if (maskBitmap == null || maskBitmap.isRecycled()) return false;

        Rect maskBounds = region.getBounds();
        RectF fill = new RectF(left, top, right, bottom);
        RectF layer = new RectF(fill);
        if (!layer.intersect(new RectF(maskBounds))) return false;

        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        int save = canvas.saveLayer(layer, null);
        try {
            canvas.drawRoundRect(fill, radiusX, radiusY, fillPaint);
            canvas.drawBitmap(maskBitmap, maskBounds.left, maskBounds.top, maskPaint);
            return true;
        } finally {
            canvas.restoreToCount(save);
            maskPaint.setXfermode(null);
        }
    }

    /**
     * Draws a free-text rounded background after subtracting the BubblePop
     * speech-balloon mask. The DST_OUT pass removes only pixels inside bubble
     * contours, leaving the rest of the free-text rectangle unchanged.
     */
    public static boolean drawExclusionClippedBackground(Canvas canvas, DetectedRegion region,
            float left, float top, float right, float bottom,
            float radiusX, float radiusY, Paint fillPaint) {
        if (canvas == null || region == null || fillPaint == null
                || right <= left || bottom <= top) return false;
        Bitmap maskBitmap = getMaskBitmap(region);
        if (maskBitmap == null || maskBitmap.isRecycled()) return false;

        Rect maskBounds = region.getBounds();
        RectF fill = new RectF(left, top, right, bottom);
        RectF overlap = new RectF(fill);
        if (!overlap.intersect(new RectF(maskBounds))) return false;

        Paint erasePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        erasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        int save = canvas.saveLayer(fill, null);
        try {
            canvas.drawRoundRect(fill, radiusX, radiusY, fillPaint);
            canvas.drawBitmap(maskBitmap, maskBounds.left, maskBounds.top, erasePaint);
            return true;
        } finally {
            canvas.restoreToCount(save);
            erasePaint.setXfermode(null);
        }
    }

    private static Bitmap getInnerBackgroundMaskBitmap(DetectedRegion region) {
        synchronized (INNER_BACKGROUND_MASK_BITMAPS) {
            Bitmap cached = INNER_BACKGROUND_MASK_BITMAPS.get(region);
            if (cached != null && !cached.isRecycled()) return cached;
            byte[] pixels = region.maskDataView();
            int width = region.getMaskWidth();
            int height = region.getMaskHeight();
            if (pixels == null || width <= 0 || height <= 0
                    || pixels.length != width * height) return null;
            byte[] inner = BubbleOverlayMasker.erodeMask(pixels, width, height, 1);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(inner));
            INNER_BACKGROUND_MASK_BITMAPS.put(region, bitmap);
            return bitmap;
        }
    }

    private static Bitmap getMaskBitmap(DetectedRegion region) {
        synchronized (MASK_BITMAPS) {
            Bitmap cached = MASK_BITMAPS.get(region);
            if (cached != null && !cached.isRecycled()) return cached;
            byte[] pixels = region.maskDataView();
            int width = region.getMaskWidth();
            int height = region.getMaskHeight();
            if (pixels == null || width <= 0 || height <= 0
                    || pixels.length != width * height) return null;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
            MASK_BITMAPS.put(region, bitmap);
            return bitmap;
        }
    }

    private static final class Line {
        final String text;
        final float x;
        final float baseline;
        Line(String text, float x, float baseline) {
            this.text = text;
            this.x = x;
            this.baseline = baseline;
        }
    }
}