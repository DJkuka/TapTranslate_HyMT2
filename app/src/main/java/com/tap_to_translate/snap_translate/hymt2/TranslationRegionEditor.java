package com.tap_to_translate.snap_translate.hymt2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Allows the user to select and remove already translated OCR regions.
 * The host's Loh/m objects are kept intact; selection is based on object identity.
 */
public final class TranslationRegionEditor {
    private static final String TAG = "TapTranslateEditor";
    private static final int EDIT_BUTTON_ID = 0x7f0a04f5;
    private static final int DELETE_BUTTON_ID = 0x7f0a04f6;
    private static final int CONTENT_ID = 0x7f0a00d3;
    private static final Map<View, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<View, State>());

    private TranslationRegionEditor() {}

    public static void bind(View root) {
        if (root == null) return;
        synchronized (STATES) {
            if (STATES.containsKey(root)) return;
            View content = root.findViewById(CONTENT_ID);
            View edit = root.findViewById(EDIT_BUTTON_ID);
            View delete = root.findViewById(DELETE_BUTTON_ID);
            if (!(content instanceof ViewGroup) || edit == null || delete == null) {
                Log.w(TAG, "Cannot bind region editor: toolbar or content view is missing");
                return;
            }

            State state = new State(root, (ViewGroup) content, edit, delete);
            SelectionOverlay overlay = new SelectionOverlay(root.getContext(), state);
            state.overlay = overlay;
            overlay.setVisibility(View.GONE);
            overlay.setClickable(true);
            overlay.setFocusable(false);

            ViewGroup.LayoutParams params;
            if (content instanceof RelativeLayout) {
                params = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
            } else {
                params = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
            }
            state.content.addView(overlay, params);

            edit.setOnClickListener(v -> toggleEditing(state));
            delete.setOnClickListener(v -> deleteSelected(state));
            state.deleteButton.setVisibility(View.GONE);
            state.editButton.setAlpha(0.55f);
            STATES.put(root, state);
            Log.i(TAG, "Region editor attached to translated overlay");
        }
    }

    /** Called when the host closes the translation panel or starts a new translation. */
    public static void forceExit(View root) {
        if (root == null) return;
        State state = STATES.get(root);
        if (state != null) exitEditing(state);
    }

    private static void toggleEditing(State state) {
        if (state.editing) {
            exitEditing(state);
            return;
        }
        List<?> regions = readList(state.root);
        if (regions == null || regions.isEmpty()) {
            toast(state.root, "Нет областей для редактирования");
            return;
        }
        state.selected.clear();
        state.editing = true;
        state.overlay.setVisibility(View.VISIBLE);
        state.overlay.bringToFront();
        state.overlay.invalidate();
        state.editButton.setAlpha(1.0f);
        state.deleteButton.setVisibility(View.VISIBLE);
        updateDeleteButton(state);
        toast(state.root, "Нажмите на рамки, чтобы выбрать области");
        Log.i(TAG, "Edit mode entered; regions=" + regions.size());
    }

    private static void exitEditing(State state) {
        if (!state.editing && state.selected.isEmpty()) {
            state.overlay.setVisibility(View.GONE);
            state.deleteButton.setVisibility(View.GONE);
            return;
        }
        state.editing = false;
        state.selected.clear();
        state.overlay.setVisibility(View.GONE);
        state.deleteButton.setVisibility(View.GONE);
        state.editButton.setAlpha(0.55f);
        state.overlay.invalidate();
        Log.i(TAG, "Edit mode exited");
    }

    private static void deleteSelected(State state) {
        if (!state.editing || state.selected.isEmpty()) {
            toast(state.root, "Сначала выберите области");
            return;
        }
        List<?> current = readList(state.root);
        if (current == null || current.isEmpty()) {
            exitEditing(state);
            return;
        }

        ArrayList<Object> remaining = new ArrayList<>(current.size());
        int removed = 0;
        for (Object item : current) {
            if (state.selected.containsKey(item)) {
                removed++;
            } else {
                remaining.add(item);
            }
        }
        if (removed == 0) {
            state.selected.clear();
            updateDeleteButton(state);
            state.overlay.invalidate();
            toast(state.root, "Выбранные области уже отсутствуют");
            return;
        }

        try {
            Method setRootList = state.root.getClass().getMethod("setList", List.class);
            setRootList.invoke(state.root, remaining);

            // rh/c.m is the normal text/background renderer used by the toolbar overlay.
            Field rendererField = state.root.getClass().getField("m");
            Object renderer = rendererField.get(state.root);
            if (renderer != null) {
                Method setRendererList = renderer.getClass().getMethod("setList", List.class);
                setRendererList.invoke(renderer, remaining);
            }

            updateSecondaryRenderer(state.root, remaining);
            state.selected.clear();
            state.overlay.invalidate();
            state.root.invalidate();
            state.content.invalidate();
            updateDeleteButton(state);

            if (remaining.isEmpty()) {
                exitEditing(state);
            }
            toast(state.root, "Удалено областей: " + removed);
            Log.i(TAG, "Deleted translated regions=" + removed + " remaining=" + remaining.size());
        } catch (Throwable error) {
            Log.e(TAG, "Could not remove selected translated regions", error);
            toast(state.root, "Не удалось удалить выбранные области");
        }
    }

    /**
     * In alternate host display mode rh/d draws the same output list in a second
     * renderer. Keep it synchronized without creating that renderer unnecessarily.
     */
    private static void updateSecondaryRenderer(View root, List<Object> remaining) {
        try {
            Field callbackField = root.getClass().getField("e");
            Object callback = callbackField.get(root);
            if (callback == null) return;

            Field serviceField = callback.getClass().getField("c");
            Object service = serviceField.get(callback);
            if (service == null) return;

            Field secondaryField = service.getClass().getField("m");
            Object secondary = secondaryField.get(service);
            if (secondary == null) return;

            secondary.getClass().getMethod("setList", List.class).invoke(secondary, remaining);
            secondary.getClass().getMethod("a").invoke(secondary);
        } catch (NoSuchFieldException ignored) {
            // The normal renderer is still updated; optional secondary mode is absent.
        } catch (Throwable error) {
            Log.w(TAG, "Secondary translated renderer was not updated", error);
        }
    }

    private static void updateDeleteButton(State state) {
        int count = state.selected.size();
        state.deleteButton.setEnabled(count > 0);
        state.deleteButton.setAlpha(count > 0 ? 1.0f : 0.35f);
        state.deleteButton.setContentDescription(
                count > 0 ? "Удалить выбранные области: " + count : "Удалить выбранные области");
    }

    private static void toast(View anchor, String message) {
        try {
            Toast.makeText(anchor.getContext(), message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static List<?> readList(View root) {
        try {
            Object value = root.getClass().getMethod("getList").invoke(root);
            return value instanceof List<?> ? (List<?>) value : null;
        } catch (Throwable error) {
            Log.w(TAG, "Could not read current translated region list", error);
            return null;
        }
    }

    private static Rect readBounds(Object item) {
        if (item == null) return null;
        try {
            Class<?> type = item.getClass();
            int left = ((Number) type.getMethod("b").invoke(item)).intValue();
            int top = ((Number) type.getMethod("d").invoke(item)).intValue();
            int right = ((Number) type.getMethod("c").invoke(item)).intValue();
            int bottom = ((Number) type.getMethod("a").invoke(item)).intValue();
            if (right <= left || bottom <= top) return null;
            return new Rect(left, top, right, bottom);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class State {
        final View root;
        final ViewGroup content;
        final View editButton;
        final View deleteButton;
        final IdentityHashMap<Object, Boolean> selected = new IdentityHashMap<>();
        SelectionOverlay overlay;
        boolean editing;

        State(View root, ViewGroup content, View editButton, View deleteButton) {
            this.root = root;
            this.content = content;
            this.editButton = editButton;
            this.deleteButton = deleteButton;
        }
    }

    private static final class SelectionOverlay extends View {
        private final State state;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float touchPadding;
        private float downX;
        private float downY;
        private final int touchSlop;

        SelectionOverlay(Context context, State state) {
            super(context);
            this.state = state;
            fill.setStyle(Paint.Style.FILL);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(dp(context, 2.0f));
            touchPadding = dp(context, 12.0f);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setWillNotDraw(false);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            List<?> regions = readList(state.root);
            if (regions == null) return;
            float radius = dp(getContext(), 5.0f);
            for (Object item : regions) {
                Rect rect = readBounds(item);
                if (rect == null) continue;
                boolean selected = state.selected.containsKey(item);
                int color = selected ? 0xFFFF8A00 : 0xFF00D8FF;
                fill.setColor(selected ? 0x44FF8A00 : 0x2200D8FF);
                outline.setColor(color);
                outline.setStrokeWidth(dp(getContext(), selected ? 3.0f : 2.0f));
                RectF bounds = new RectF(rect);
                canvas.drawRoundRect(bounds, radius, radius, fill);
                canvas.drawRoundRect(bounds, radius, radius, outline);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!state.editing) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if ((dx * dx) + (dy * dy) <= touchSlop * touchSlop) {
                        toggleAt(event.getX(), event.getY());
                    }
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        private void toggleAt(float x, float y) {
            List<?> regions = readList(state.root);
            if (regions == null || regions.isEmpty()) return;

            Object best = null;
            long bestArea = Long.MAX_VALUE;
            for (int i = regions.size() - 1; i >= 0; i--) {
                Object item = regions.get(i);
                Rect rect = readBounds(item);
                if (rect == null) continue;
                RectF expanded = new RectF(
                        rect.left - touchPadding, rect.top - touchPadding,
                        rect.right + touchPadding, rect.bottom + touchPadding);
                if (!expanded.contains(x, y)) continue;
                long area = (long) rect.width() * rect.height();
                if (area < bestArea) {
                    best = item;
                    bestArea = area;
                }
            }
            if (best == null) return;

            if (state.selected.containsKey(best)) state.selected.remove(best);
            else state.selected.put(best, Boolean.TRUE);

            updateDeleteButton(state);
            invalidate();
        }
    }

    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics());
    }
}
