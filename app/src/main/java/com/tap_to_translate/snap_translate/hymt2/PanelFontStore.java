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
    /*
     * A renderer reuses its TextPaint for every overlay. Remember the last
     * typeface applied by this class separately from the host's current base
     * typeface. The old implementation cached the first typeface forever,
     * which caused every subsequent draw to undo changes made in app settings.
     */
    private static final Map<TextPaint, FontState> FONT_STATES =
            Collections.synchronizedMap(new WeakHashMap<TextPaint, FontState>());

    private PanelFontStore() { }

    private static final class FontState {
        Typeface baseTypeface;
        Typeface lastAppliedOverride;

        FontState(Typeface base) {
            baseTypeface = base;
        }
    }

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

    /**
     * Apply a per-panel override without freezing the global font. If the host
     * changes TextPaint while this renderer is active, that new typeface becomes
     * the baseline. When a panel has no override, restore that current baseline
     * after a preceding panel's override instead of restoring the first-ever font.
     */
    public static void apply(TextPaint paint, Object region) {
        if (paint == null) return;
        synchronized (FONT_STATES) {
            Typeface current = paint.getTypeface();
            FontState state = FONT_STATES.get(paint);
            if (state == null) {
                state = new FontState(current);
                FONT_STATES.put(paint, state);
            } else if (state.lastAppliedOverride == null
                    || current != state.lastAppliedOverride) {
                // The host has supplied a new global typeface since our last
                // override (or since the previous draw). Keep it as the baseline.
                state.baseTypeface = current;
            }

            Typeface override = region == null ? null : FONT_OVERRIDES.get(region);
            if (override != null) {
                if (current != override) paint.setTypeface(override);
                state.lastAppliedOverride = override;
            } else {
                Typeface base = state.baseTypeface;
                if (current != base) paint.setTypeface(base);
                state.lastAppliedOverride = null;
            }
        }
    }
}
