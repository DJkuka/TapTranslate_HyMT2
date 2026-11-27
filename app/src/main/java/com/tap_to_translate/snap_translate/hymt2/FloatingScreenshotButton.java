package com.tap_to_translate.snap_translate.hymt2;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Passive camera button for the host floating window.
 * The host sh/b listener retains ownership of movement and edge snapping.
 */
public final class FloatingScreenshotButton {
    private static final String TAG = "TapTranslateFloatShot";
    private static final String PREFS = "taptranslate_screenshot_settings";
    private static final String KEY_ENABLED = "floating_screenshot_button_enabled";
    private static final String BUTTON_TAG = "taptranslate_floating_screenshot_button";
    private static final int ICON_CONTAINER_ID = 0x7f0a04dc;
    private static final Map<Object, TouchState> TOUCHES =
            Collections.synchronizedMap(new WeakHashMap<Object, TouchState>());
    private static final Map<View, Boolean> WIDGETS =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private FloatingScreenshotButton() { }

    /** Add a passive sibling. It must never become a touch target itself. */
    public static void attach(View widget, Object ignoredRegister) {
        if (widget == null) return;
        WIDGETS.put(widget, Boolean.TRUE);
        try {
            View container = widget.findViewById(ICON_CONTAINER_ID);
            if (container == null || !(container.getParent() instanceof ViewGroup)) {
                Log.w(TAG, "Floating icon container is not ready");
                return;
            }
            ViewGroup row = (ViewGroup) container.getParent();
            View camera = row.findViewWithTag(BUTTON_TAG);
            if (camera == null) {
                Context context = widget.getContext();
                ImageView image = new ImageView(context);
                image.setTag(BUTTON_TAG);
                image.setContentDescription("Сделать скриншот");
                image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                int icon = context.getResources().getIdentifier(
                        "ic_screenshot_camera", "drawable", context.getPackageName());
                image.setImageResource(icon != 0 ? icon : android.R.drawable.ic_menu_camera);
                image.setColorFilter(Color.WHITE);
                int size = dp(context, 38);
                int inset = dp(context, 8);
                image.setPadding(inset, inset, inset, inset);
                GradientDrawable background = new GradientDrawable();
                background.setShape(GradientDrawable.OVAL);
                background.setColor(0xCC202020);
                background.setStroke(dp(context, 1), 0x99FFFFFF);
                image.setBackground(background);
                image.setAlpha(0.9f);

                // A non-clickable child lets all MotionEvents bubble to the
                // floating window and its original OnTouchListener.
                image.setClickable(false);
                image.setLongClickable(false);
                image.setFocusable(false);
                image.setFocusableInTouchMode(false);
                ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(size, size);
                row.addView(image, params);
                camera = image;
            }
            camera.setVisibility(isEnabled(widget.getContext()) ? View.VISIBLE : View.GONE);
        } catch (Throwable error) {
            Log.e(TAG, "Could not attach floating screenshot button", error);
        }
    }

    /** Called before the host's own sh/b.onTouch logic. */
    public static void onHostTouch(Object listener, MotionEvent event) {
        if (listener == null || event == null) return;
        try {
            View widget = (View) readField(listener, "c");
            if (widget == null) return;
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                View camera = findButton(widget);
                if (isInside(camera, event)) {
                    TOUCHES.put(listener, new TouchState(widget, event.getRawX(), event.getRawY()));
                } else {
                    TOUCHES.remove(listener);
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                TouchState state = TOUCHES.get(listener);
                if (state != null) {
                    float dx = Math.abs(event.getRawX() - state.downX);
                    float dy = Math.abs(event.getRawY() - state.downY);
                    int slop = android.view.ViewConfiguration.get(widget.getContext())
                            .getScaledTouchSlop();
                    if (dx > slop || dy > slop) state.moved = true;
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                TOUCHES.remove(listener);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Could not track screenshot-button touch", error);
        }
    }

    /** Called after native drag/snap processing and before GestureDetector. */
    public static boolean consumeCameraGesture(Object listener, MotionEvent event) {
        if (listener == null || event == null) return false;
        TouchState state = TOUCHES.get(listener);
        if (state == null) return false;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP) {
            TOUCHES.remove(listener);
            if (!state.moved) {
                Object service = resolveService(state.widget);
                if (service != null) {
                    TranslationScreenshotManager.captureFromService(service);
                } else {
                    Toast.makeText(state.widget.getContext().getApplicationContext(),
                            "Не удалось получить службу перевода", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            TOUCHES.remove(listener);
            return true;
        }
        return true;
    }

    /** Persist the OCR-settings switch and refresh already-created floating widgets. */
    public static void updateEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();

        synchronized (WIDGETS) {
            for (View widget : WIDGETS.keySet()) {
                if (widget == null) continue;
                widget.post(() -> {
                    View camera = findButton(widget);
                    if (camera != null) {
                        camera.setVisibility(enabled ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }
    }

    private static boolean isInside(View view, MotionEvent event) {
        if (view == null || view.getVisibility() != View.VISIBLE ||
                view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x < location[0] + view.getWidth()
                && y >= location[1] && y < location[1] + view.getHeight();
    }

    private static View findButton(View widget) {
        View container = widget.findViewById(ICON_CONTAINER_ID);
        if (container == null || !(container.getParent() instanceof ViewGroup)) return null;
        return ((ViewGroup) container.getParent()).findViewWithTag(BUTTON_TAG);
    }

    private static Object resolveService(View widget) {
        Object wrapper = readField(widget, "g");
        Object service = readField(wrapper, "c");
        return service instanceof Context ? service : null;
    }

    private static Object readField(Object instance, String name) {
        if (instance == null) return null;
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (Throwable error) {
                Log.w(TAG, "Could not read " + name, error);
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

    private static final class TouchState {
        final View widget;
        final float downX;
        final float downY;
        boolean moved;

        TouchState(View widget, float downX, float downY) {
            this.widget = widget;
            this.downX = downX;
            this.downY = downY;
        }
    }
}
