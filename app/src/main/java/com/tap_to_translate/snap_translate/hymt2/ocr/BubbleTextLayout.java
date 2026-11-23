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
 * Shape-aware text layout over a cropped BubblePop instance mask.
 *
 * This deliberately does not clip glyphs. Every rendered line is measured against
 * a horizontal interval that is inside the mask for the complete line-height band.
 * The full source text must fit before anything is drawn; otherwise the caller may
 * use its existing fallback layout.
 */
public final class BubbleTextLayout {
    private static final String TAG = "TapTranslateBubbleLayout";
    private static final Map<DetectedRegion, Bitmap> MASK_BITMAPS =
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
        if (canvas == null || mask == null || maskWidth <= 0 || maskHeight <= 0
                || mask.length != maskWidth * maskHeight || maskBounds == null
                || maskBounds.width() != maskWidth || maskBounds.height() != maskHeight
                || overlayBounds == null || source == null || paint == null
                || source.length() == 0) return false;

        // The mask has already been localized to this OCR overlay. Use its
        // complete interior, not the source OCR rectangle, as the layout area.
        Rect area = new Rect(maskBounds);
        int verticalInset = 2;
        int horizontalPadding = Math.max(2, padding);
        area.inset(verticalInset, verticalInset);
        area.offset(Math.round(offsetX), Math.round(offsetY));
        // Clamp the text's available layout area to the same detected container.
        // The actual glyph bands are still checked against the per-pixel mask.
        if (area.left < maskBounds.left) area.offset(maskBounds.left - area.left, 0);
        if (area.top < maskBounds.top) area.offset(0, maskBounds.top - area.top);
        if (area.right > maskBounds.right) area.offset(maskBounds.right - area.right, 0);
        if (area.bottom > maskBounds.bottom) area.offset(0, maskBounds.bottom - area.bottom);
        if (area.width() < 4 || area.height() < 4) return false;

        float originalSize = paint.getTextSize();
        float safeScale = Math.max(0.55f, Math.min(1.65f, fontScale));
        float upper = Math.max(minTextSize, maxTextSize) * safeScale;
        float lower = Math.max(4.0f, Math.min(minTextSize * safeScale, upper));
        try {
            for (float size = upper; size >= lower - 0.01f; size -= 0.5f) {
                paint.setTextSize(size);
                String layoutText = source.toString();
                if (!autoWrap) layoutText = layoutText.replace('\n', ' ').replace('\r', ' ');
                List<Line> lines = layout(mask, maskWidth, maskHeight, maskBounds,
                        area, layoutText, paint, horizontalPadding, autoWrap);
                if (lines != null && !lines.isEmpty()) {
                    android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
                    float usedTop = lines.get(0).baseline + fm.ascent;
                    float usedBottom = lines.get(lines.size() - 1).baseline + fm.descent;
                    float verticalShift = (area.top + area.bottom - usedTop - usedBottom) / 2f;
                    android.graphics.Paint.Align oldAlign = paint.getTextAlign();
                    paint.setTextAlign(android.graphics.Paint.Align.LEFT);
                    for (Line line : lines) {
                        canvas.drawText(line.text, line.x, line.baseline + verticalShift, paint);
                    }
                    paint.setTextAlign(oldAlign);
                    Log.i(TAG, "shape_layout=fit lines=" + lines.size()
                            + " font=" + size + " area=" + area.flattenToString());
                    return true;
                }
            }
            if (!autoWrap) {
                // Keep the text inside the mask if a one-line layout cannot fit
                // even at the minimum font size. This is the safe last resort.
                for (float size = upper; size >= lower - 0.01f; size -= 0.5f) {
                    paint.setTextSize(size);
                    List<Line> lines = layout(mask, maskWidth, maskHeight, maskBounds,
                            area, source.toString(), paint, horizontalPadding, true);
                    if (lines == null || lines.isEmpty()) continue;
                    android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
                    float usedTop = lines.get(0).baseline + fm.ascent;
                    float usedBottom = lines.get(lines.size() - 1).baseline + fm.descent;
                    float verticalShift = (area.top + area.bottom - usedTop - usedBottom) / 2f;
                    android.graphics.Paint.Align oldAlign = paint.getTextAlign();
                    paint.setTextAlign(android.graphics.Paint.Align.LEFT);
                    for (Line line : lines) {
                        canvas.drawText(line.text, line.x, line.baseline + verticalShift, paint);
                    }
                    paint.setTextAlign(oldAlign);
                    Log.i(TAG, "shape_layout=wrap_fallback lines=" + lines.size()
                            + " font=" + size);
                    return true;
                }
            }
            Log.i(TAG, "shape_layout=not_fit text_length=" + source.length()
                    + " mask=" + maskWidth + "x" + maskHeight
                    + " overlay=" + overlayBounds.flattenToString());
            return false;
        } finally {
            paint.setTextSize(originalSize);
        }
    }

    private static List<Line> layout(byte[] mask, int width, int height, Rect maskBounds,
            Rect area, String text, TextPaint paint, int padding, boolean autoWrap) {
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        int lineHeight = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent + fm.leading));
        int scanStep = Math.max(1, lineHeight / 4);
        int y = area.top;
        int offset = 0;
        ArrayList<Line> result = new ArrayList<>();

        while (offset < text.length()) {
            if (text.charAt(offset) == '\n') {
                offset++;
                y += lineHeight;
                continue;
            }
            while (offset < text.length() && Character.isWhitespace(text.charAt(offset))
                    && text.charAt(offset) != '\n') offset++;
            if (offset >= text.length()) break;
            if (y + lineHeight > area.bottom) return null;

            int paragraphEnd = text.indexOf('\n', offset);
            if (paragraphEnd < 0) paragraphEnd = text.length();

            // Build lines from complete words only. Never let breakText() split the
            // first word (or any later word) at an arbitrary character boundary.
            boolean placed = false;
            while (!placed && y + lineHeight <= area.bottom) {
                int[] run = widestCommonRun(mask, width, height, maskBounds, area,
                        y, lineHeight, padding);
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
                y += lineHeight;
                placed = true;
            }
            if (!placed && y + lineHeight > area.bottom) return null;
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
            Rect area, int top, int lineHeight, int padding) {
        int y0 = Math.max(area.top, top);
        int y1 = Math.min(Math.min(area.bottom, top + lineHeight), maskBounds.bottom);
        if (y1 <= y0) return null;
        int x0 = Math.max(area.left, maskBounds.left);
        int x1 = Math.min(area.right, maskBounds.right);
        int bestStart = -1, bestEnd = -1, runStart = -1;
        for (int x = x0; x < x1; x++) {
            boolean inside = true;
            for (int y = y0; y < y1; y++) {
                int ix = x - maskBounds.left;
                int iy = y - maskBounds.top;
                if (ix < 0 || ix >= width || iy < 0 || iy >= height
                        || mask[iy * width + ix] == 0) {
                    inside = false;
                    break;
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
        Bitmap maskBitmap = getMaskBitmap(region);
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