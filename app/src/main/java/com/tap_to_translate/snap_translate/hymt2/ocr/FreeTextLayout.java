package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** Rectangle-based text layout for detector-classified free-text overlays. */
public final class FreeTextLayout {
    private FreeTextLayout() {}
    public static boolean draw(Canvas canvas, Rect bounds, CharSequence source,
            TextPaint paint, float minTextSize, float maxTextSize, int padding,
            float offsetX, float offsetY, float fontScale, float lineSpacing) {
        if (canvas == null || bounds == null || source == null || source.length() == 0
                || paint == null || bounds.width() < 4 || bounds.height() < 4) return false;
        float originalSize = paint.getTextSize();
        int inset = Math.max(2, padding), width = bounds.width() - inset * 2;
        int height = bounds.height() - inset * 2;
        if (width < 2 || height < 2) return false;
        float min = Math.max(4f, Math.min(minTextSize, maxTextSize));
        float max = Math.max(minTextSize, maxTextSize);
        float spacing = Math.max(0.85f, Math.min(3.0f, lineSpacing));
        float scale = Math.max(0.25f, Math.min(2.50f, fontScale));
        try {
            float base = 0f;
            StaticLayout baseLayout = null;
            for (float size = max; size >= min - 0.01f; size -= 0.5f) {
                paint.setTextSize(size);
                StaticLayout candidate = makeLayout(source, paint, width, spacing);
                if (candidate != null && candidate.getHeight() <= height) {
                    base = size; baseLayout = candidate; break;
                }
            }
            if (baseLayout == null) return false;
            // The natural fit gives us a stable starting size. The user's scale is
            // applied after that fit and is not reduced back to the original panel
            // height; the panel clips overflow while the text remains visibly scalable.
            float wanted = Math.max(min, base * scale);
            paint.setTextSize(wanted);
            StaticLayout result = makeLayout(source, paint, width, spacing);
            if (result == null) return false;
            int save = canvas.save();
            try {
                canvas.clipRect(bounds);
                canvas.translate(bounds.left + inset + offsetX,
                        bounds.top + inset + offsetY
                                + (height - result.getHeight()) * 0.5f);
                result.draw(canvas);
            } finally { canvas.restoreToCount(save); }
            return true;
        } finally { paint.setTextSize(originalSize); }
    }
    private static StaticLayout makeLayout(CharSequence text, TextPaint paint,
            int width, float spacing) {
        try {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false).setLineSpacing(0f, spacing).build();
        } catch (Throwable ignored) { return null; }
    }
}
