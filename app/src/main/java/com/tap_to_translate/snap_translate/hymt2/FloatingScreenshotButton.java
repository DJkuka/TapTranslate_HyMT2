package com.tap_to_translate.snap_translate.hymt2;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;


/**
 * Adds an optional screenshot control to the host floating-icon window.
 * The control is a sibling of the main icon, so the host WindowManager drag
 * and edge-snapping logic moves both controls together.
 */
public final class FloatingScreenshotButton {
    private static final String TAG = "TapTranslateFloatShot";
    private static final String PREFS = "taptranslate_screenshot_settings";
    private static final String KEY_ENABLED = "floating_screenshot_button_enabled";
    private static final String BUTTON_TAG = "taptranslate_floating_screenshot_button";
    private static final int ICON_CONTAINER_ID = 0x7f0a04dc;
    private static final int MAIN_ICON_ID = 0x7f0a01af;

    private FloatingScreenshotButton() { }

    public static void attach(View widget, Object service) {
        if (widget == null || service == null) return;
        try {
            View iconContainer = widget.findViewById(ICON_CONTAINER_ID);
            if (iconContainer == null || !(iconContainer.getParent() instanceof ViewGroup)) {
                Log.w(TAG, "Floating icon container is not ready");
                return;
            }
            ViewGroup row = (ViewGroup) iconContainer.getParent();
            View existing = row.findViewWithTag(BUTTON_TAG);
            if (existing != null) {
                existing.setVisibility(isEnabled(widget.getContext()) ? View.VISIBLE : View.GONE);
                return;
            }

            Context context = widget.getContext();
            ImageView button = new ImageView(context);
            button.setTag(BUTTON_TAG);
            button.setContentDescription("Сделать скриншот");
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int icon = context.getResources().getIdentifier(
                    "ic_screenshot_camera", "drawable", context.getPackageName());
            button.setImageResource(icon != 0 ? icon : android.R.drawable.ic_menu_camera);
            button.setColorFilter(Color.WHITE);
            int size = dp(context, 38);
            int inset = dp(context, 8);
            button.setPadding(inset, inset, inset, inset);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0xCC202020);
            background.setStroke(dp(context, 1), 0x99FFFFFF);
            button.setBackground(background);
            button.setAlpha(0.9f);
            button.setClickable(true);
            button.setFocusable(true);
            if (Build.VERSION.SDK_INT >= 26) {
                button.setTooltipText("Скриншот экрана перевода");
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.gravity = Gravity.CENTER_VERTICAL;
            params.leftMargin = dp(context, 5);
            row.addView(button, params);

            button.setOnClickListener(view ->
                    TranslationScreenshotManager.captureFromService(service));
            // The primary ImageView already owns the short-tap action. Attach
            // the toggle to that same view so a long press cannot be lost to
            // child-touch dispatch on the floating widget's root layout.
            View mainIcon = widget.findViewById(MAIN_ICON_ID);
            if (mainIcon != null) {
                mainIcon.setOnLongClickListener(view -> {
                    showToggleMenu(widget);
                    return true;
                });
            }
            button.setVisibility(isEnabled(context) ? View.VISIBLE : View.GONE);
        } catch (Throwable error) {
            Log.e(TAG, "Could not attach floating screenshot button", error);
        }
    }

    /** Opens from a long press on the main floating icon. */
    public static void showToggleMenu(View widget) {
        if (widget == null) return;
        try {
            Context context = widget.getContext();
            View anchor = widget.findViewById(MAIN_ICON_ID);
            if (anchor == null) anchor = widget;
            PopupMenu menu = new PopupMenu(context, anchor);
            boolean enabled = isEnabled(context);
            MenuItem toggle = menu.getMenu().add("Показывать кнопку снимка");
            toggle.setCheckable(true);
            toggle.setChecked(enabled);
            menu.setOnMenuItemClickListener(item -> {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                boolean next = !prefs.getBoolean(KEY_ENABLED, false);
                prefs.edit().putBoolean(KEY_ENABLED, next).apply();
                View button = findButton(widget);
                if (button != null) button.setVisibility(next ? View.VISIBLE : View.GONE);
                item.setChecked(next);
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

    private static View findButton(View widget) {
        View iconContainer = widget.findViewById(ICON_CONTAINER_ID);
        if (iconContainer == null || !(iconContainer.getParent() instanceof ViewGroup)) return null;
        return ((ViewGroup) iconContainer.getParent()).findViewWithTag(BUTTON_TAG);
    }

    private static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
