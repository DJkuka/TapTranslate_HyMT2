package com.tap_to_translate.snap_translate.hymt2;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Floating camera button integrated with the host floating window's touch
 * stream. Both icons remain part of one draggable WindowManager view.
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

    /** Called after the host has installed its original click listener. */
    public static void attach(View widget, Object ignoredHostRegister) {
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

                // Important: neither child consumes the host touch stream.
                // sh/b remains the single source of drag, edge-snap and tap handling.
                camera.setClickable(false);
                camera.setFocusable(false);
                camera.setLongClickable(false);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    camera.setTooltipText("Скриншот экрана перевода");
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
                params.gravity = Gravity.CENTER_VERTICAL;
                params.leftMargin = dp(context, 5);
                row.addView(camera, params);
                button = camera;
            }

            button.setVisibility(isEnabled(widget.getContext()) ? View.VISIBLE : View.GONE);

            // Keep the original click listener attached, but let pointer events
            // bubble to FloatingIconWidget so dragging also works from the icon.
        } catch (Throwable error) {
            Log.e(TAG, "Could not attach floating screenshot button", error);
        }
    }

    /**
     * Receives the same MotionEvents as the host sh/b listener, after its native
     * drag/snap processing. Returning true suppresses the host tap detector for
     * gestures which began on one of our two icon surfaces.
     */
    public static boolean handleTouch(Object touchListener, MotionEvent event) {
        if (touchListener == null || event == null) return false;
        try {
            View widget = (View) readField(touchListener, "c");
            if (widget == null) return false;
            int action = event.getActionMasked();
            TouchState state;

            if (action == MotionEvent.ACTION_DOWN) {
                View camera = findButton(widget);
                View mainIcon = widget.findViewById(MAIN_ICON_ID);
                View target = isInside(camera, event) ? camera : null;
                if (target == null) {
                    TOUCHES.remove(touchListener);
                    return false;
                }
                int kind = TouchState.CAMERA;
                state = new TouchState(widget, target, kind,
                        event.getRawX(), event.getRawY());
                TOUCHES.put(touchListener, state);
                return true;
            }

            state = TOUCHES.get(touchListener);
            if (state == null) return false;

            if (action == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(event.getRawX() - state.downX);
                float dy = Math.abs(event.getRawY() - state.downY);
                int slop = ViewConfiguration.get(widget.getContext()).getScaledTouchSlop();
                if (dx > slop || dy > slop) {
                    state.moved = true;
                    cancelLongPress(state);
                }
                return true;
            }

            if (action == MotionEvent.ACTION_UP) {
                TOUCHES.remove(touchListener);
                cancelLongPress(state);
                if (!state.moved && !state.longPressed) {
                    if (state.kind == TouchState.CAMERA) {
                        Object service = resolveService(widget);
                        if (service != null) {
                            TranslationScreenshotManager.captureFromService(service);
                        } else {
                            Toast.makeText(widget.getContext().getApplicationContext(),
                                    "Не удалось получить службу перевода",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                return true;
            }

            if (action == MotionEvent.ACTION_CANCEL) {
                TOUCHES.remove(touchListener);
                cancelLongPress(state);
                return true;
            }
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Floating icon touch routing failed", error);
            return false;
        }
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
                Log.w(TAG, "Could not read " + name + " from " + instance.getClass().getName(), error);
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
        static final int MAIN_ICON = 1;
        static final int CAMERA = 2;
        final View widget;
        final View target;
        final int kind;
        final float downX;
        final float downY;
        boolean moved;
        boolean longPressed;
        Runnable longPress;

        TouchState(View widget, View target, int kind, float downX, float downY) {
            this.widget = widget;
            this.target = target;
            this.kind = kind;
            this.downX = downX;
            this.downY = downY;
        }
    }
}
