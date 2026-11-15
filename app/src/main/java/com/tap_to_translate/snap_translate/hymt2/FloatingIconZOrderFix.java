package com.tap_to_translate.snap_translate.hymt2;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.reflect.Field;

/**
 * Re-stacks the existing floating icon above the full-screen translation window.
 * The icon's live WindowManager.LayoutParams are reused, preserving its position.
 */
public final class FloatingIconZOrderFix {
    private static final String TAG = "TapTranslateOverlay";
    private FloatingIconZOrderFix() {}

    public static void bringToFront(Object service) {
        if (service == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> bringToFrontNow(service));
        } else {
            bringToFrontNow(service);
        }
    }

    private static void bringToFrontNow(Object service) {
        View icon = null;
        WindowManager manager = null;
        ViewGroup.LayoutParams params = null;
        boolean reattachFlagSet = false;
        try {
            Field iconField = service.getClass().getField("j");
            Object iconValue = iconField.get(service);
            if (!(iconValue instanceof View)) return;
            icon = (View) iconValue;
            if (icon.getWindowToken() == null || icon.getParent() == null) return;

            Field managerField = service.getClass().getField("b");
            Object managerValue = managerField.get(service);
            if (!(managerValue instanceof WindowManager)) return;
            manager = (WindowManager) managerValue;

            params = icon.getLayoutParams();
            if (params == null) {
                Log.w(TAG, "Floating icon has no live layout params; z-order not changed");
                return;
            }

            Field preserve = icon.getClass().getField("hymt2PreserveOnReattach");
            preserve.setBoolean(icon, true);
            reattachFlagSet = true;

            manager.removeView(icon);
            manager.addView(icon, params);
            reattachFlagSet = false; // onDetachedFromWindow consumed and reset the flag
            Log.i(TAG, "Floating icon re-added above the translation window");
        } catch (Throwable error) {
            Log.w(TAG, "Could not re-stack floating icon", error);
            if (reattachFlagSet && icon != null) {
                try {
                    icon.getClass().getField("hymt2PreserveOnReattach").setBoolean(icon, false);
                } catch (Throwable ignored) {
                }
            }
            if (manager != null && icon != null && params != null
                    && icon.getWindowToken() == null && icon.getParent() == null) {
                try {
                    manager.addView(icon, params);
                    Log.i(TAG, "Floating icon restored after z-order attempt");
                } catch (Throwable restoreError) {
                    Log.e(TAG, "Floating icon restoration failed", restoreError);
                }
            }
        }
    }
}
