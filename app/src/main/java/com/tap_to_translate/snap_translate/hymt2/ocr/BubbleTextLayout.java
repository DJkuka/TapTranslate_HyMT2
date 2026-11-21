package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.TextPaint;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

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
    private BubbleTextLayout() {}

    public static boolean draw(Canvas canvas, byte[] mask, int maskWidth, int maskHeight,
            Rect maskBounds, Rect overlayBounds, CharSequence source, TextPaint paint,
            float minTextSize, float maxTextSize, int padding) {
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
        if (area.width() < 4 || area.height() < 4) return false;

        float originalSize = paint.getTextSize();
        float upper = Math.max(minTextSize, maxTextSize);
        float lower = Math.max(4.0f, Math.min(minTextSize, upper));
        try {
            for (float size = upper; size >= lower - 0.01f; size -= 0.5f) {
                paint.setTextSize(size);
                List<Line> lines = layout(mask, maskWidth, maskHeight, maskBounds,
                        area, source.toString(), paint, horizontalPadding);
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
            Log.i(TAG, "shape_layout=not_fit text_length=" + source.length()
                    + " mask=" + maskWidth + "x" + maskHeight
                    + " overlay=" + overlayBounds.flattenToString());
            return false;
        } finally {
            paint.setTextSize(originalSize);
        }
    }

    private static List<Line> layout(byte[] mask, int width, int height, Rect maskBounds,
            Rect area, String text, TextPaint paint, int padding) {
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        int lineHeight = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent + fm.leading));
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

            int[] run = null;
            int minimumRun = Math.max(4, (int) Math.ceil(paint.getTextSize() * 0.45f));
            int scanStep = Math.max(1, lineHeight / 4);
            while (y + lineHeight <= area.bottom) {
                run = widestCommonRun(mask, width, height, maskBounds, area,
                        y, lineHeight, padding);
                if (run != null && run[1] - run[0] >= minimumRun) break;
                // Ignore narrow tips/indentations at the edge of irregular balloons;
                // start the next text line where a complete glyph band fits.
                y += scanStep;
            }
            if (run == null || run[1] - run[0] < minimumRun) return null;
            float available = run[1] - run[0];
            int paragraphEnd = text.indexOf('\n', offset);
            if (paragraphEnd < 0) paragraphEnd = text.length();
            int count = paint.breakText(text, offset, paragraphEnd, true, available, null);
            if (count <= 0) return null;
            int end = Math.min(paragraphEnd, offset + count);
            if (end < paragraphEnd) {
                int whitespace = -1;
                for (int i = end - 1; i > offset; i--) {
                    if (Character.isWhitespace(text.charAt(i))) {
                        whitespace = i;
                        break;
                    }
                }
                if (whitespace > offset) end = whitespace;
            }
            while (end > offset && Character.isWhitespace(text.charAt(end - 1))) end--;
            if (end <= offset) return null;
            String lineText = text.substring(offset, end);
            float textWidth = paint.measureText(lineText);
            if (textWidth > available + 0.5f) return null;
            float x = run[0] + (available - textWidth) / 2f;
            result.add(new Line(lineText, x, y - fm.ascent));
            offset = end;
            while (offset < text.length() && text.charAt(offset) != '\n'
                    && Character.isWhitespace(text.charAt(offset))) offset++;
            if (offset < text.length() && text.charAt(offset) == '\n') offset++;
            y += lineHeight;
        }
        return result;
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