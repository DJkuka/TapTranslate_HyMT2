package com.tap_to_translate.snap_translate.hymt2;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Optional camera button that shares the host floating window's touch stream.
 * The host sh/b listener remains responsible for all movement and edge snapping.
 */
public final class FloatingScreenshotButton {
    private static final String TAG = "TapTranslateFloatShot";
    private static final String PREFS = "taptranslate_screenshot_settings";
    private static final String KEY_ENABLED = "floating_screenshot_button_enabled";
    private static final String BUTTON_TAG = "taptranslate_floating_screenshot_button";
    private static final int ICON_CONTAINER_ID = 0x7f0a04dc;
    private static final int MAIN_ICON_ID = 0x7f0a01af;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, TouchState> TOUCHES =
            Collections.synchronizedMap(new WeakHashMap<Object, TouchState>());

    private FloatingScreenshotButton() { }

    /** Add the camera as a passive child; it must not consume the host's touch stream. */
    public static void attach(View widget, Object ignoredRegister) {
        if (widget == null) return;
        try {
            View iconContainer = widget.findViewById(ICON_CONTAINER_ID);
            if (iconContainer == null || !(iconContainer.getParent() instanceof ViewGroup)) {
                Log.w(TAG, "Floating icon container is not ready");
                return;
            }
            ViewGroup row = (ViewGroup) iconContainer.getParent();
            View button = row.findViewWithTag(BUTTON_TAG);
            if (button == null) {
                Context context = widget.getContext();
                ImageView camera = new ImageView(context);
                camera.setTag(BUTTON_TAG);
                camera.setContentDescription("Сделать скриншот");
                camera.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                int icon = context.getResources().getIdentifier(
                        "ic_screenshot_camera", "drawable", context.getPackageName());
                camera.setImageResource(icon != 0 ? icon : android.R.drawable.ic_menu_camera);
                camera.setColorFilter(Color.WHITE);
                int size = dp(context, 38);
                int inset = dp(context, 8);
                camera.setPadding(inset, inset, inset, inset);
                GradientDrawable background = new GradientDrawable();
                background.setShape(GradientDrawable.OVAL);
                background.setColor(0xCC202020);
                background.setStroke(dp(context, 1), 0x99FFFFFF);
                camera.setBackground(background);
                camera.setAlpha(0.9f);

                // Critical: this child must never become the touch target.
                // Its events then bubble to the host FloatingIconWidget listener.
                camera.setClickable(false);
                camera.setLongClickable(false);
                camera.setFocusable(false);
                camera.setFocusableInTouchMode(false);
                ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(size, size);
                row.addView(camera, params);
                button = camera;
            }
            button.setVisibility(isEnabled(widget.getContext()) ? View.VISIBLE : View.GONE);
        } catch (Throwable error) {
            Log.e(TAG, "Could not attach floating screenshot button", error);
        }
    }

    /**
     * Called at the beginning of sh/b.onTouch. Only records the touched region;
     * always lets the host perform its original movement, snapping and state logic.
     */
    public static void onHostTouch(Object listener, MotionEvent event) {
        if (listener == null || event == null) return;
        try {
            View widget = (View) readField(listener, "c");
            if (widget == null) return;
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                View camera = findButton(widget);
                View mainIcon = widget.findViewById(MAIN_ICON_ID);
                int kind = isInside(camera, event) ? TouchState.CAMERA
                        : (isInside(mainIcon, event) ? TouchState.MAIN_ICON : TouchState.OTHER);
                if (kind == TouchState.OTHER) {
                    TOUCHES.remove(listener);
                    return;
                }

                TouchState state = new TouchState(widget, kind,
                        event.getRawX(), event.getRawY());
                TOUCHES.put(listener, state);
                if (kind == TouchState.MAIN_ICON) {
                    state.longPress = () -> {
                        if (TOUCHES.get(listener) == state && !state.moved) {
                            state.longPressed = true;
                            showToggleMenu(widget);
                        }
                    };
                    MAIN.postDelayed(state.longPress,
                            ViewConfiguration.getLongPressTimeout());
                }
            } else {
                TouchState state = TOUCHES.get(listener);
                if (state == null) return;
                if (action == MotionEvent.ACTION_MOVE) {
                    float dx = Math.abs(event.getRawX() - state.downX);
                    float dy = Math.abs(event.getRawY() - state.downY);
                    int slop = ViewConfiguration.get(widget.getContext()).getScaledTouchSlop();
                    if (dx > slop || dy > slop) {
                        state.moved = true;
                        cancelLongPress(state);
                    }
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    cancelLongPress(state);
                    TOUCHES.remove(listener);
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "Could not track floating icon touch", error);
        }
    }

    /**
     * Called after the host has processed each event but immediately before
     * GestureDetector. Camera gestures skip only GestureDetector's icon action;
     * host drag/edge snapping always runs. A tap saves the current frame.
     */
    public static boolean consumeCameraGesture(Object listener, MotionEvent event) {
        if (listener == null || event == null) return false;
        TouchState state = TOUCHES.get(listener);
        if (state == null || state.kind != TouchState.CAMERA) return false;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP) {
            TOUCHES.remove(listener);
            cancelLongPress(state);
            if (!state.moved) {
                Object service = resolveService(state.widget);
                if (service != null) {
                    TranslationScreenshotManager.captureFromService(service);
                } else {
                    Toast.makeText(state.widget.getContext().getApplicationContext(),
                            "Не удалось получить службу перевода", Toast.LENGTH_SHORT).show();
                }
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            TOUCHES.remove(listener);
            cancelLongPress(state);
        }
        return true;
    }

    public static void showToggleMenu(View widget) {
        if (widget == null) return;
        try {
            Context context = widget.getContext();
            View anchor = widget.findViewById(MAIN_ICON_ID);
            if (anchor == null) anchor = widget;
            PopupMenu menu = new PopupMenu(context, anchor);
            boolean enabled = isEnabled(context);
            android.view.MenuItem toggle = menu.getMenu().add("Показывать кнопку снимка");
            toggle.setCheckable(true);
            toggle.setChecked(enabled);
            menu.setOnMenuItemClickListener(item -> {
                boolean next = !isEnabled(context);
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_ENABLED, next).apply();
                View button = findButton(widget);
                if (button != null) button.setVisibility(next ? View.VISIBLE : View.GONE);
                Toast.makeText(context.getApplicationContext(),
                        next ? "Кнопка снимка включена" : "Кнопка снимка отключена",
                        Toast.LENGTH_SHORT).show();
                return true;
            });
            menu.show();
        } catch (Throwable error) {
            Log.e(TAG, "Could not show floating screenshot menu", error);
            Toast.makeText(widget.getContext().getApplicationContext(),
                    "Не удалось открыть меню кнопки снимка", Toast.LENGTH_SHORT).show();
        }
    }

    private static void cancelLongPress(TouchState state) {
        if (state.longPress != null) {
            MAIN.removeCallbacks(state.longPress);
            state.longPress = null;
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
        View iconContainer = widget.findViewById(ICON_CONTAINER_ID);
        if (iconContainer == null || !(iconContainer.getParent() instanceof ViewGroup)) return null;
        return ((ViewGroup) iconContainer.getParent()).findViewWithTag(BUTTON_TAG);
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
        static final int OTHER = 0;
        static final int MAIN_ICON = 1;
        static final int CAMERA = 2;
        final View widget;
        final int kind;
        final float downX;
        final float downY;
        boolean moved;
        boolean longPressed;
        Runnable longPress;

        TouchState(View widget, int kind, float downX, float downY) {
            this.widget = widget;
            this.kind = kind;
            this.downX = downX;
            this.downY = downY;
        }
    }
}
