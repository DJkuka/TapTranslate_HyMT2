package com.tap_to_translate.snap_translate.hymt2;

import android.content.Context;
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
    private static final Map<Object, Typeface> FONT_OVERRIDES =
            Collections.synchronizedMap(new WeakHashMap<Object, Typeface>());
    private static final Map<String, Typeface> ASSET_TYPEFACES =
            Collections.synchronizedMap(new java.util.HashMap<String, Typeface>());
    private static final Map<TextPaint, Typeface> ORIGINAL_TYPEFACES =
            Collections.synchronizedMap(new WeakHashMap<TextPaint, Typeface>());

    private PanelFontStore() { }

    public static void setFont(Object region, Typeface typeface) {
        if (region == null) return;
        if (typeface == null) FONT_OVERRIDES.remove(region);
        else FONT_OVERRIDES.put(region, typeface);
    }

    /** Loads one of the original Tap Translate fonts bundled in assets/fonts. */
    public static Typeface loadTypeface(Context context, String assetPath) {
        if (context == null || assetPath == null || assetPath.length() == 0) {
            return Typeface.DEFAULT;
        }
        synchronized (ASSET_TYPEFACES) {
            Typeface cached = ASSET_TYPEFACES.get(assetPath);
            if (cached != null) return cached;
            Typeface loaded = Typeface.createFromAsset(context.getAssets(), "fonts/" + assetPath);
            ASSET_TYPEFACES.put(assetPath, loaded);
            return loaded;
        }
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
        Typeface override = region == null ? null : FONT_OVERRIDES.get(region);
        paint.setTypeface(override == null ? original : override);
    }
}
