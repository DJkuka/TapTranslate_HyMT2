package com.tap_to_translate.snap_translate.hymt2;

import android.graphics.Typeface;
import android.text.TextPaint;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * In-memory per-region font overrides. These are intentionally not written
 * to preferences or style presets and disappear with their region objects.
 */
public final class PanelFontStore {
    private static final Map<Object, String> FONT_OVERRIDES =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final Map<TextPaint, Typeface> ORIGINAL_TYPEFACES =
            Collections.synchronizedMap(new WeakHashMap<TextPaint, Typeface>());

    private PanelFontStore() { }

    public static void setFont(Object region, String family) {
        if (region == null) return;
        if (family == null || family.length() == 0) FONT_OVERRIDES.remove(region);
        else FONT_OVERRIDES.put(region, family);
    }

    public static String getFont(Object region) {
        return region == null ? null : FONT_OVERRIDES.get(region);
    }

    /** Called once for each region immediately before its StaticLayout is measured. */
    public static void apply(TextPaint paint, Object region) {
        if (paint == null) return;
        Typeface original;
        synchronized (ORIGINAL_TYPEFACES) {
            if (!ORIGINAL_TYPEFACES.containsKey(paint)) {
                ORIGINAL_TYPEFACES.put(paint, paint.getTypeface());
            }
            original = ORIGINAL_TYPEFACES.get(paint);
        }
        String family = getFont(region);
        paint.setTypeface(family == null ? original : Typeface.create(family, Typeface.NORMAL));
    }
}
