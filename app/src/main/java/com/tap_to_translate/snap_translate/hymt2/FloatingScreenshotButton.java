package com.tap_to_translate.snap_translate.hymt2;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Independent screenshot control in its own secure overlay window.
 * It never shares the host floating icon's touch target or gesture listener.
 */
public final class FloatingScreenshotButton {
    private static final String TAG = "TapTranslateFloatShot";
    private static final String PREFS = "taptranslate_screenshot_settings";
    private static final String KEY_ENABLED = "floating_screenshot_button_enabled";
    private static final String KEY_X = "floating_screenshot_button_x";
    private static final String KEY_Y = "floating_screenshot_button_y";
    private static final String SERVICE_CLASS =
            "com.tap_to_translate.snap_translate.domain.main.service.ScreenTranslateService";
    private static final int BUTTON_SIZE_DP = 46;
    private static final int EDGE_MARGIN_DP = 14;

    private static final Map<View, Boolean> WIDGETS =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static final Map<View, ButtonState> BUTTONS =
            Collections.synchronizedMap(new WeakHashMap<View, ButtonState>());

    private FloatingScreenshotButton() { }

    /** Called when the host floating icon is attached to its WindowManager. */
    public static void attach(View widget) {
        if (widget == null) return;
        WIDGETS.put(widget, Boolean.TRUE);
        if (isEnabled(widget.getContext())) {
            widget.post(() -> show(widget));
        }
    }

    /** Called when the host floating icon is detached or re-stacked. */
    public static void detach(View widget) {
        if (widget == null) return;
        WIDGETS.remove(widget);
        hide(widget);
    }

    /** Persist the OCR-settings checkbox and update any currently running service. */
    public static void updateEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();

        ArrayList<View> snapshot;
        synchronized (WIDGETS) {
            snapshot = new ArrayList<>(WIDGETS.keySet());
        }
        for (View widget : snapshot) {
            if (widget == null) continue;
            widget.post(() -> {
                if (enabled) show(widget);
                else hide(widget);
            });
        }
    }

    /** Re-clamp the movable button after a display rotation or size change. */
    public static void refresh(Context context) {
        if (context == null) return;
        ArrayList<Map.Entry<View, ButtonState>> snapshot;
        synchronized (BUTTONS) {
            snapshot = new ArrayList<>(BUTTONS.entrySet());
        }
        for (Map.Entry<View, ButtonState> entry : snapshot) {
            View widget = entry.getKey();
            ButtonState state = entry.getValue();
            if (widget == null || state == null) continue;
            state.button.post(() -> clampAndUpdate(state));
        }
    }

    private static void show(View widget) {
        if (widget == null || !isEnabled(widget.getContext())) return;
        synchronized (BUTTONS) {
            ButtonState existing = BUTTONS.get(widget);
            if (existing != null && existing.button.getParent() != null) return;
        }

        Context service = resolveService(widget);
        if (service == null) {
            Log.w(TAG, "ScreenTranslateService reference is not available from the floating icon");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(service)) {
            toast(service, "Для кнопки скриншота необходимо разрешение «Поверх других окон»");
            return;
        }

        try {
            WindowManager manager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) throw new IllegalStateException("WindowManager недоступен");

            DisplayMetrics metrics = new DisplayMetrics();
            Display display = manager.getDefaultDisplay();
            display.getRealMetrics(metrics);
            int size = dp(service, BUTTON_SIZE_DP);
            SharedPreferences prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int defaultX = Math.max(0, metrics.widthPixels - size - dp(service, EDGE_MARGIN_DP));
            int defaultY = Math.max(dp(service, 96), metrics.heightPixels / 2 - size / 2);

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_SECURE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    size, size, type, flags, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = clamp(prefs.getInt(KEY_X, defaultX), 0, Math.max(0, metrics.widthPixels - size));
            params.y = clamp(prefs.getInt(KEY_Y, defaultY), 0, Math.max(0, metrics.heightPixels - size));
            params.setTitle("TapTranslate screenshot button");

            ImageView camera = new ImageView(service);
            camera.setContentDescription("Сделать скриншот");
            camera.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            camera.setImageResource(android.R.drawable.ic_menu_camera);
            camera.setColorFilter(Color.WHITE);
            int inset = dp(service, 10);
            camera.setPadding(inset, inset, inset, inset);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0xE6202020);
            background.setStroke(dp(service, 1), 0xAAFFFFFF);
            camera.setBackground(background);
            camera.setAlpha(0.94f);
            camera.setClickable(true);
            camera.setFocusable(false);

            ButtonState state = new ButtonState(service, manager, camera, params, prefs,
                    metrics.widthPixels, metrics.heightPixels,
                    ViewConfiguration.get(service).getScaledTouchSlop());
            camera.setOnClickListener(v ->
                    TranslationScreenshotManager.captureFromService(state.service));
            camera.setOnTouchListener((view, event) -> handleTouch(state, view, event));

            manager.addView(camera, params);
            synchronized (BUTTONS) {
                BUTTONS.put(widget, state);
            }
            Log.i(TAG, "Independent secure screenshot button attached");
        } catch (Throwable error) {
            Log.e(TAG, "Could not show floating screenshot button", error);
            toast(service, "Не удалось показать кнопку скриншота: " +
                    (error.getMessage() == null ? "ошибка" : error.getMessage()));
        }
    }

    private static boolean handleTouch(ButtonState state, View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                state.downX = event.getRawX();
                state.downY = event.getRawY();
                state.startX = state.params.x;
                state.startY = state.params.y;
                state.moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - state.downX;
                float dy = event.getRawY() - state.downY;
                if (!state.moved && (Math.abs(dx) > state.touchSlop || Math.abs(dy) > state.touchSlop)) {
                    state.moved = true;
                }
                if (state.moved) {
                    state.params.x = clamp(state.startX + Math.round(dx), 0,
                            Math.max(0, state.screenWidth - view.getWidth()));
                    state.params.y = clamp(state.startY + Math.round(dy), 0,
                            Math.max(0, state.screenHeight - view.getHeight()));
                    try {
                        state.windowManager.updateViewLayout(view, state.params);
                    } catch (Throwable error) {
                        Log.w(TAG, "Could not move screenshot button", error);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (state.moved) {
                    state.preferences.edit()
                            .putInt(KEY_X, state.params.x)
                            .putInt(KEY_Y, state.params.y)
                            .apply();
                } else {
                    view.performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    private static void clampAndUpdate(ButtonState state) {
        DisplayMetrics metrics = new DisplayMetrics();
        state.windowManager.getDefaultDisplay().getRealMetrics(metrics);
        state.screenWidth = metrics.widthPixels;
        state.screenHeight = metrics.heightPixels;
        state.params.x = clamp(state.params.x, 0, Math.max(0, metrics.widthPixels - state.button.getWidth()));
        state.params.y = clamp(state.params.y, 0, Math.max(0, metrics.heightPixels - state.button.getHeight()));
        try {
            state.windowManager.updateViewLayout(state.button, state.params);
        } catch (Throwable error) {
            Log.w(TAG, "Could not refresh screenshot button position", error);
        }
    }

    private static void hide(View widget) {
        ButtonState state;
        synchronized (BUTTONS) {
            state = BUTTONS.remove(widget);
        }
        if (state == null) return;
        try {
            if (state.button.getParent() != null) state.windowManager.removeView(state.button);
        } catch (Throwable error) {
            Log.w(TAG, "Could not remove floating screenshot button", error);
        }
    }

    private static Context resolveService(View widget) {
        Object wrapper = readField(widget, "g");
        Object candidate = readField(wrapper, "c");
        if (isService(candidate)) return (Context) candidate;
        if (isService(widget.getContext())) return widget.getContext();

        Object found = findService(candidate, Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>()), 0);
        if (found instanceof Context) return (Context) found;
        return null;
    }

    private static Object findService(Object value, Set<Object> visited, int depth) {
        if (value == null || depth > 4 || !visited.add(value)) return null;
        if (isService(value)) return value;
        if (value instanceof View || value instanceof android.graphics.Bitmap ||
                value instanceof String || value.getClass().isPrimitive()) return null;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object nested = field.get(value);
                    Object found = findService(nested, visited, depth + 1);
                    if (found != null) return found;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static boolean isService(Object value) {
        return value instanceof Context && SERVICE_CLASS.equals(value.getClass().getName());
    }

    private static Object readField(Object instance, String name) {
        if (instance == null) return null;
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException missing) {
                // Continue with the superclass.
            } catch (Throwable error) {
                Log.w(TAG, "Could not read host field " + name, error);
                return null;
            }
        }
        return null;
    }

    private static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    private static final class ButtonState {
        final Context service;
        final WindowManager windowManager;
        final ImageView button;
        final WindowManager.LayoutParams params;
        final SharedPreferences preferences;
        final int touchSlop;
        int screenWidth;
        int screenHeight;
        float downX;
        float downY;
        int startX;
        int startY;
        boolean moved;

        ButtonState(Context service, WindowManager manager, ImageView button,
                    WindowManager.LayoutParams params, SharedPreferences preferences,
                    int screenWidth, int screenHeight, int touchSlop) {
            this.service = service;
            this.windowManager = manager;
            this.button = button;
            this.params = params;
            this.preferences = preferences;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.touchSlop = touchSlop;
        }
    }
}
