package com.tap_to_translate.snap_translate.hymt2;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
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
 * Edits the geometry and translated text of existing host overlay regions.
 *
 * The host renderer already performs a StaticLayout-based font-size fit for
 * every region, so changing the model bounds and invalidating the renderer
 * automatically reflows and resizes the translated text.
 */
public final class TranslationRegionEditor {
    private static final String TAG = "TapTranslateEditor";
    private static final int EDIT_BUTTON_ID = 0x7f0a04f5;
    private static final int DELETE_BUTTON_ID = 0x7f0a04f6;
    private static final int EDIT_TEXT_BUTTON_ID = 0x7f0a04f7;
    private static final int ADD_REGION_BUTTON_ID = 0x7f0a04f8;
    private static final int CONTENT_ID = 0x7f0a00d3;
    private static final int TEXT_PLACEMENT_BUTTON_ID = 0x7f0a04f9;
    private static final int FREE_TEXT_OPACITY_BUTTON_ID = 0x7f0a04fa;
    private static final String BRIDGE = "com.tap_to_translate.snap_translate.hymt2.ocr.RtDetrLiveBridge";
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
            View editText = root.findViewById(EDIT_TEXT_BUTTON_ID);
            View addRegion = root.findViewById(ADD_REGION_BUTTON_ID);
            View textPlacement = root.findViewById(TEXT_PLACEMENT_BUTTON_ID);
            View opacity = root.findViewById(FREE_TEXT_OPACITY_BUTTON_ID);
            if (!(content instanceof ViewGroup) || edit == null || delete == null) {
                Log.w(TAG, "Cannot bind region editor: toolbar or content view is missing");
                return;
            }

            State state = new State(root, (ViewGroup) content, edit, delete, editText, opacity);
            SelectionOverlay overlay = new SelectionOverlay(root.getContext(), state);
            state.overlay = overlay;
            overlay.setVisibility(View.GONE);
            overlay.setClickable(true);
            overlay.setFocusable(false);

            ViewGroup.LayoutParams params;
            if (content instanceof android.widget.RelativeLayout) {
                params = new android.widget.RelativeLayout.LayoutParams(
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
            if (editText != null) {
                editText.setOnClickListener(v -> editSelectedText(state));
                editText.setVisibility(View.GONE);
            }
            if (addRegion != null) {
                addRegion.setOnClickListener(v -> addRegion(state));
                addRegion.setVisibility(View.GONE);
            }
            state.addRegionButton = addRegion;
            state.textPlacementButton = textPlacement;
            if (opacity != null) {
                opacity.setOnClickListener(v -> editFreeTextOpacity(state));
                opacity.setVisibility(View.GONE);
            }
            if (textPlacement != null) {
                textPlacement.setOnClickListener(v -> toggleTextPlacementMode(state));
                textPlacement.setVisibility(View.GONE);
            }
            state.deleteButton.setVisibility(View.GONE);
            state.editButton.setAlpha(0.55f);
            STATES.put(root, state);
            Log.i(TAG, "Region editor attached: selection, geometry and text editing enabled");
        }
    }

    /** Called when the host closes the translation panel. */
    public static void forceExit(View root) {
        if (root == null) return;
        State state = STATES.get(root);
        if (state != null) {
            exitEditing(state);
            state.originalTexts.clear();
            state.minimumResizeSizes.clear();
        }
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
        state.transformTarget = null;
        state.transformMode = false;
        state.textTransformMode = false;
        state.editing = true;
        state.overlay.setVisibility(View.VISIBLE);
        if (state.addRegionButton != null) state.addRegionButton.setVisibility(View.VISIBLE);
        state.overlay.bringToFront();
        state.overlay.invalidate();
        state.editButton.setAlpha(1.0f);
        updateSelectionButtons(state);
        toast(state.root, "Касание выбирает область; двойное касание включает перемещение и изменение размера");
        Log.i(TAG, "Edit mode entered; regions=" + regions.size());
    }

    private static void exitEditing(State state) {
        state.editing = false;
        state.transformMode = false;
        state.textTransformMode = false;
        state.transformTarget = null;
        state.selected.clear();
        state.overlay.setVisibility(View.GONE);
        state.deleteButton.setVisibility(View.GONE);
        if (state.editTextButton != null) state.editTextButton.setVisibility(View.GONE);
        if (state.addRegionButton != null) state.addRegionButton.setVisibility(View.GONE);
        if (state.textPlacementButton != null) state.textPlacementButton.setVisibility(View.GONE);
        if (state.opacityButton != null) state.opacityButton.setVisibility(View.GONE);
        state.editButton.setAlpha(0.55f);
        state.overlay.invalidate();
        Log.i(TAG, "Edit mode exited");
    }

    private static void enterTransformMode(State state, Object item) {
        if (item == null) return;
        Rect initialBounds = readBounds(item);
        if (initialBounds == null) return;
        state.selected.clear();
        state.selected.put(item, Boolean.TRUE);
        state.transformTarget = item;
        state.transformMode = true;
        state.textTransformMode = false;
        // Remember a safe minimum for the lifetime of this overlay. For narrow
        // source regions the normal minimum (36x28 dp) would be larger than the
        // original, making it impossible to shrink an expanded region back.
        if (!state.minimumResizeSizes.containsKey(item)) {
            // Use a small common minimum for every region. Preserve an even
            // smaller original dimension so existing tiny balloons are not forced
            // to grow just to enter geometry-edit mode.
            int minWidth = Math.max(1, Math.round(dp(state.root.getContext(), 16.0f)));
            int minHeight = Math.max(1, Math.round(dp(state.root.getContext(), 16.0f)));
            state.minimumResizeSizes.put(item, new int[] {
                    Math.min(minWidth, initialBounds.width()),
                    Math.min(minHeight, initialBounds.height())
            });
        }
        state.overlay.lastTapTarget = null;
        state.overlay.lastTapTime = 0L;
        updateSelectionButtons(state);
        state.overlay.invalidate();
        toast(state.root, "Два пальца в любых местах экрана — перемещение; угловые маркеры — изменение размера");
        Log.i(TAG, "Geometry edit mode entered for one region");
    }

    private static void exitTransformMode(State state) {
        state.transformMode = false;
        state.transformTarget = null;
        state.overlay.lastTapTarget = null;
        state.overlay.lastTapTime = 0L;
        state.overlay.clearDrag();
        updateSelectionButtons(state);
        state.overlay.invalidate();
        toast(state.root, "Режим изменения рамки завершён");
    }

    private static void addRegion(State state) {
        if (!state.editing) return;
        try {
            int width = Math.max(1, Math.round(dp(state.root.getContext(), 120.0f)));
            int height = Math.max(1, Math.round(dp(state.root.getContext(), 56.0f)));
            int left = Math.max(0, (state.overlay.getWidth() - width) / 2);
            int top = Math.max(0, (state.overlay.getHeight() - height) / 2);

            // Loh.m has no zero-argument constructor. Use its real Rect/String
            // constructor so text, coordinates and the internal list are initialized.
            Class<?> modelClass = Class.forName("oh.m");
            android.graphics.Rect bounds = new android.graphics.Rect(
                    left, top, left + width, top + height);
            Object item = modelClass
                    .getConstructor(android.graphics.Rect.class, String.class)
                    .newInstance(bounds, "");
            List<?> current = readList(state.root);
            ArrayList<Object> updated = current == null ? new ArrayList<>() : new ArrayList<Object>(current);
            updated.add(item);
            state.selected.clear();
            state.selected.put(item, Boolean.TRUE);
            state.originalTexts.put(item, "");
            state.minimumResizeSizes.put(item, new int[] { Math.max(1, Math.round(dp(state.root.getContext(), 16.0f))), Math.max(1, Math.round(dp(state.root.getContext(), 16.0f))) });
            state.transformTarget = item;
            state.transformMode = true;
            state.root.getClass().getMethod("setList", List.class).invoke(state.root, updated);
            Field rendererField = state.root.getClass().getField("m");
            Object renderer = rendererField.get(state.root);
            if (renderer != null) renderer.getClass().getMethod("setList", List.class).invoke(renderer, updated);
            updateSecondaryRenderer(state.root, updated);
            refreshOverlayAssociation(item);
            state.overlay.lastTapTarget = null;
            updateSelectionButtons(state);
            refreshRenderers(state);
            toast(state.root, "Добавлена пустая панель. Нажми карандаш, чтобы ввести текст");
            Log.i(TAG, "Added empty translated region at " + left + "," + top);
        } catch (Throwable error) {
            Log.e(TAG, "Could not add translated region", error);
            toast(state.root, "Не удалось добавить панель");
        }
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
                state.originalTexts.remove(item);
                state.minimumResizeSizes.remove(item);
                clearFreeTextBackgroundAlpha(item);
            } else {
                remaining.add(item);
            }
        }
        if (removed == 0) {
            state.selected.clear();
            updateSelectionButtons(state);
            state.overlay.invalidate();
            toast(state.root, "Выбранные области уже отсутствуют");
            return;
        }

        state.transformMode = false;
        state.transformTarget = null;
        try {
            Method setRootList = state.root.getClass().getMethod("setList", List.class);
            setRootList.invoke(state.root, remaining);

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
            updateSelectionButtons(state);

            if (remaining.isEmpty()) exitEditing(state);
            toast(state.root, "Удалено областей: " + removed);
            Log.i(TAG, "Deleted translated regions=" + removed + " remaining=" + remaining.size());
        } catch (Throwable error) {
            Log.e(TAG, "Could not remove selected translated regions", error);
            toast(state.root, "Не удалось удалить выбранные области");
        }
    }

    private static void updateSelectionButtons(State state) {
        int count = state.selected.size();
        state.deleteButton.setVisibility(state.editing ? View.VISIBLE : View.GONE);
        state.deleteButton.setEnabled(count > 0);
        state.deleteButton.setAlpha(count > 0 ? 1.0f : 0.35f);
        state.deleteButton.setContentDescription(
                count > 0 ? "Удалить выбранные области: " + count : "Удалить выбранные области");

        boolean oneRegion = state.editing && count == 1;
        boolean bubbleRegion = oneRegion
                && hasBubbleMask(state.selected.keySet().iterator().next());
        boolean freeTextRegion = oneRegion
                && isFreeTextOverlay(state.selected.keySet().iterator().next());
        if (state.editTextButton != null) {
            state.editTextButton.setVisibility(oneRegion ? View.VISIBLE : View.GONE);
            state.editTextButton.setEnabled(oneRegion);
            state.editTextButton.setContentDescription("Редактировать текст перевода");
        }
        if (state.textPlacementButton != null) {
            boolean textEditable = bubbleRegion || freeTextRegion;
            state.textPlacementButton.setVisibility(textEditable ? View.VISIBLE : View.GONE);
            state.textPlacementButton.setEnabled(textEditable);
            state.textPlacementButton.setAlpha(state.textTransformMode ? 1.0f : 0.55f);
            state.textPlacementButton.setContentDescription("Размер и межстрочный интервал текста");
        }
        if (state.opacityButton != null) {
            state.opacityButton.setVisibility(oneRegion ? View.VISIBLE : View.GONE);
            state.opacityButton.setEnabled(oneRegion);
            state.opacityButton.setAlpha(oneRegion ? 1.0f : 0.35f);
            state.opacityButton.setContentDescription("Непрозрачность заливки панели");
        }
    }

    private static boolean isFreeTextOverlay(Object item) {
        try {
            return Boolean.TRUE.equals(Class.forName(BRIDGE)
                    .getMethod("isFreeTextOverlay", Object.class).invoke(null, item));
        } catch (Throwable error) {
            Log.w(TAG, "Could not classify selected free-text overlay", error);
            return false;
        }
    }

    private static void clearFreeTextBackgroundAlpha(Object item) {
        try {
            Class.forName(BRIDGE).getMethod("clearFreeTextBackgroundAlpha", Object.class)
                    .invoke(null, item);
        } catch (Throwable error) {
            Log.w(TAG, "Could not clear free-text alpha override", error);
        }
    }

    private static boolean hasBubbleMask(Object item) {
        try {
            return Boolean.TRUE.equals(Class.forName(BRIDGE)
                    .getMethod("hasBubbleMask", Object.class).invoke(null, item));
        } catch (Throwable error) {
            return false;
        }
    }

    private static void refreshOverlayAssociation(Object item) {
        try {
            Class.forName(BRIDGE).getMethod("refreshOverlayAssociation", Object.class)
                    .invoke(null, item);
        } catch (Throwable error) {
            Log.w(TAG, "Could not associate editor panel with OCR regions", error);
        }
    }

    private static void toggleTextPlacementMode(State state) {
        if (!state.editing || state.selected.size() != 1) {
            toast(state.root, "Выберите одну область");
            return;
        }
        if (state.textTransformMode) {
            state.textTransformMode = false;
            state.transformTarget = null;
            state.overlay.clearDrag();
            updateSelectionButtons(state);
            state.overlay.invalidate();
            toast(state.root, "Коррекция текста завершена");
            return;
        }
        state.transformTarget = state.selected.keySet().iterator().next();
        state.transformMode = false;
        state.textTransformMode = true;
        state.overlay.clearDrag();
        updateSelectionButtons(state);
        state.overlay.invalidate();
        toast(state.root, "Один палец: горизонтально — размер, вертикально — интервал; два пальца — перемещение текста");
    }

    private static float[] getTextTransform(Object item) {
        try {
            return (float[]) Class.forName(BRIDGE).getMethod("getBubbleTextTransform", Object.class)
                    .invoke(null, item);
        } catch (Throwable error) {
            Log.w(TAG, "Could not read BubblePop text transform", error);
            return new float[] {0f, 0f, 1f};
        }
    }

    private static void setTextTransform(
            Object item, float dx, float dy, float scale, float lineSpacing) {
        try {
            Class.forName(BRIDGE).getMethod("setBubbleTextTransform",
                    Object.class, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE)
                    .invoke(null, item, dx, dy, scale, lineSpacing);
        } catch (Throwable error) {
            Log.w(TAG, "Could not update BubblePop text transform", error);
        }
    }

    private static void editSelectedText(State state) {
        if (!state.editing || state.selected.size() != 1) {
            toast(state.root, "Для редактирования текста выберите одну область");
            return;
        }
        Object item = state.selected.keySet().iterator().next();
        String current = readStringField(item, "b");
        if (current == null) current = "";
        if (!state.originalTexts.containsKey(item)) {
            state.originalTexts.put(item, current);
        }
        final String original = state.originalTexts.get(item);
        final String initialText = current;

        try {
            Context context = state.root.getContext();
            EditText editor = new EditText(context);
            editor.setText(initialText);
            editor.setSelection(editor.length());
            editor.setMinLines(4);
            editor.setMaxLines(10);
            editor.setGravity(Gravity.TOP | Gravity.START);
            editor.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            editor.setHint("Текст перевода");
            editor.setVerticalScrollBarEnabled(true);
            editor.setPadding(Math.round(dp(context, 12)), Math.round(dp(context, 10)), Math.round(dp(context, 12)), Math.round(dp(context, 10)));

            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(Math.round(dp(context, 8)), Math.round(dp(context, 4)), Math.round(dp(context, 8)), Math.round(dp(context, 4)));
            container.addView(editor, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("Редактировать перевод")
                    .setView(container)
                    .setPositiveButton("Сохранить", null)
                    .setNegativeButton("Отмена", null)
                    .setNeutralButton("Исходный перевод", null)
                    .create();

            // The host panel is a system overlay created by a Service, not an Activity.
            // Use the same overlay window type so the editor can safely obtain IME focus.
            if (dialog.getWindow() != null && android.os.Build.VERSION.SDK_INT >= 26) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }

            dialog.setOnShowListener(ignored -> {
                Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                Button restore = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

                save.setOnClickListener(v -> {
                    String updated = editor.getText() == null ? "" : editor.getText().toString();
                    if (writeStringField(item, "b", updated)) {
                        refreshOverlayAssociation(item);
                        refreshRenderers(state);
                        toast(state.root, "Текст области обновлён");
                        dialog.dismiss();
                    } else {
                        toast(state.root, "Не удалось сохранить текст области");
                    }
                });
                cancel.setOnClickListener(v -> dialog.dismiss());
                restore.setOnClickListener(v -> {
                    editor.setText(original == null ? "" : original);
                    editor.setSelection(editor.length());
                });

                editor.requestFocus();
                editor.post(() -> {
                    try {
                        InputMethodManager imm = (InputMethodManager)
                                context.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                    } catch (Throwable error) {
                        Log.w(TAG, "Could not show keyboard for translated text editor", error);
                    }
                });
            });
            dialog.show();
            Log.i(TAG, "Opened translated text editor");
        } catch (Throwable error) {
            Log.e(TAG, "Could not open translated text editor", error);
            toast(state.root, "Не удалось открыть редактор текста");
        }
    }


    private static void editFreeTextOpacity(State state) {
        if (!state.editing || state.selected.size() != 1) {
            toast(state.root, "Выберите одну панель");
            return;
        }
        final Object item = state.selected.keySet().iterator().next();

        final Context context = state.root.getContext();
        final int originalHostAlpha = readHostBackgroundAlpha();
        final int originalOverride = readFreeTextAlphaOverride(item);
        final int initialPercent = readFreeTextAlphaPercent(context, item, originalHostAlpha);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontal = Math.round(dp(context, 20));
        // Keep the dialog compact vertically; preserve its current width.
        container.setPadding(horizontal, Math.round(dp(context, 4)),
                horizontal, 0);

        TextView explanation = new TextView(context);
        explanation.setText("Меняется только заливка, не сам текст.");
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        container.addView(explanation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView valueLabel = new TextView(context);
        valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        valueLabel.setPadding(0, Math.round(dp(context, 4)), 0, 0);
        container.addView(valueLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(100);
        seekBar.setProgress(initialPercent);
        container.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(dp(context, 32))));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Непрозрачность фона панели")
                .setView(container)
                .setPositiveButton("Готово", null)
                .setNegativeButton("Отмена", null)
                .create();

        if (dialog.getWindow() != null && android.os.Build.VERSION.SDK_INT >= 26) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }

        final boolean[] accepted = new boolean[] { false };
        Runnable restorePrevious = () -> {
            if (originalOverride >= 0) {
                setFreeTextAlphaPercent(item, originalOverride);
            } else {
                clearFreeTextBackgroundAlpha(item);
            }
            invalidateRendererViews(state);
        };

        valueLabel.setText(formatFreeTextAlpha(initialPercent));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int percent = clamp(progress, 0, 100);
                valueLabel.setText(formatFreeTextAlpha(percent));
                setFreeTextAlphaPercent(item, percent);
                invalidateRendererViews(state);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            save.setOnClickListener(v -> {
                accepted[0] = true;
                toast(state.root, "Непрозрачность фона сохранена для выбранного блока");
                dialog.dismiss();
            });
            cancel.setOnClickListener(v -> {
                restorePrevious.run();
                dialog.dismiss();
            });
        });
        dialog.setOnCancelListener(ignored -> {
            if (!accepted[0]) restorePrevious.run();
        });
        dialog.show();
    }

    private static String formatFreeTextAlpha(int percent) {
        int value = clamp(percent, 0, 100);
        return "Непрозрачность заливки: " + value + "%  ·  Прозрачность: " + (100 - value) + "%";
    }

    private static int readHostBackgroundAlpha() {
        try {
            Class<?> owner = Class.forName("ca.d2");
            Method method = owner.getDeclaredMethod("a", Boolean.TYPE);
            method.setAccessible(true);
            Object value = method.invoke(null, false);
            return value instanceof Number ? clamp(((Number) value).intValue(), 0, 255) : 255;
        } catch (Throwable error) {
            Log.w(TAG, "Could not read default host fill alpha; using opaque default", error);
            return 255;
        }
    }

    private static int readFreeTextAlphaOverride(Object item) {
        try {
            Object value = Class.forName(BRIDGE)
                    .getMethod("getFreeTextBackgroundAlphaOverridePercent", Object.class)
                    .invoke(null, item);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable error) {
            Log.w(TAG, "Could not read free-text alpha override", error);
            return -1;
        }
    }

    private static int readFreeTextAlphaPercent(Context context, Object item, int originalAlpha) {
        try {
            Object value = Class.forName(BRIDGE).getMethod(
                    "getFreeTextBackgroundAlphaPercent", Context.class, Object.class, Integer.TYPE)
                    .invoke(null, context, item, originalAlpha);
            return value instanceof Number ? clamp(((Number) value).intValue(), 0, 100) : 100;
        } catch (Throwable error) {
            Log.w(TAG, "Could not read free-text alpha", error);
            return clamp(Math.round(originalAlpha * 100f / 255f), 0, 100);
        }
    }

    private static void setFreeTextAlphaPercent(Object item, int percent) {
        try {
            Class.forName(BRIDGE).getMethod(
                    "setFreeTextBackgroundAlphaPercent", Object.class, Integer.TYPE)
                    .invoke(null, item, clamp(percent, 0, 100));
        } catch (Throwable error) {
            Log.w(TAG, "Could not set free-text alpha", error);
        }
    }

    private static void invalidateRendererViews(State state) {
        try {
            Field rendererField = state.root.getClass().getField("m");
            Object renderer = rendererField.get(state.root);
            if (renderer instanceof View) ((View) renderer).invalidate();
        } catch (Throwable error) {
            Log.w(TAG, "Could not invalidate primary text renderer", error);
        }
        try {
            Field callbackField = state.root.getClass().getField("e");
            Object callback = callbackField.get(state.root);
            Field serviceField = callback.getClass().getField("c");
            Object service = serviceField.get(callback);
            Field secondaryField = service.getClass().getField("m");
            Object secondary = secondaryField.get(service);
            if (secondary instanceof View) ((View) secondary).invalidate();
        } catch (NoSuchFieldException ignored) {
            // The alternate renderer is optional.
        } catch (Throwable error) {
            Log.w(TAG, "Could not invalidate secondary text renderer", error);
        }
        state.root.invalidate();
        state.content.invalidate();
    }

    private static void refreshRenderers(State state) {
        List<?> current = readList(state.root);
        if (current == null) return;
        try {
            state.root.getClass().getMethod("setList", List.class).invoke(state.root, current);
        } catch (Throwable error) {
            Log.w(TAG, "Root region list refresh failed", error);
        }
        try {
            Field rendererField = state.root.getClass().getField("m");
            Object renderer = rendererField.get(state.root);
            if (renderer != null) {
                renderer.getClass().getMethod("setList", List.class).invoke(renderer, current);
                if (renderer instanceof View) ((View) renderer).invalidate();
            }
        } catch (Throwable error) {
            Log.w(TAG, "Primary text renderer refresh failed", error);
        }
        updateSecondaryRenderer(state.root, new ArrayList<Object>(current));
        state.root.invalidate();
        state.content.invalidate();
        state.overlay.invalidate();
    }

    /**
     * The alternate host display mode may draw through a second renderer.
     * Keep that renderer in sync without creating it when it is not active.
     */
    private static void updateSecondaryRenderer(View root, List<Object> regions) {
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

            secondary.getClass().getMethod("setList", List.class).invoke(secondary, regions);
            secondary.getClass().getMethod("a").invoke(secondary);
            if (secondary instanceof View) ((View) secondary).invalidate();
        } catch (NoSuchFieldException ignored) {
            // The normal renderer is still updated; optional secondary mode is absent.
        } catch (Throwable error) {
            Log.w(TAG, "Secondary translated renderer was not updated", error);
        }
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

    private static int[] readRawBounds(Object item) {
        try {
            Class<?> type = item.getClass();
            return new int[] {
                    type.getField("c").getInt(item),
                    type.getField("d").getInt(item),
                    type.getField("e").getInt(item),
                    type.getField("f").getInt(item)
            };
        } catch (Throwable error) {
            Log.w(TAG, "Could not read writable region geometry", error);
            return null;
        }
    }

    private static void writeGeometry(Object item, Rect start, int[] rawStart, Rect updated) {
        try {
            Class<?> type = item.getClass();
            type.getField("c").setInt(item, rawStart[0] + updated.left - start.left);
            type.getField("d").setInt(item, rawStart[1] + updated.right - start.right);
            type.getField("e").setInt(item, rawStart[2] + updated.top - start.top);
            type.getField("f").setInt(item, rawStart[3] + updated.bottom - start.bottom);
        } catch (Throwable error) {
            Log.e(TAG, "Could not update translated region geometry", error);
        }
    }

    private static String readStringField(Object item, String name) {
        try {
            Object value = item.getClass().getField(name).get(item);
            return value instanceof String ? (String) value : null;
        } catch (Throwable error) {
            Log.w(TAG, "Could not read translated text field " + name, error);
            return null;
        }
    }

    private static boolean writeStringField(Object item, String name, String value) {
        try {
            item.getClass().getField(name).set(item, value);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Could not update translated text field " + name, error);
            return false;
        }
    }

    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics());
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) max = min;
        return Math.max(min, Math.min(max, value));
    }

    private static final class State {
        final View root;
        final ViewGroup content;
        final View editButton;
        final View deleteButton;
        final View editTextButton;
        View addRegionButton;
        View textPlacementButton;
        final View opacityButton;
        final IdentityHashMap<Object, Boolean> selected = new IdentityHashMap<>();
        final IdentityHashMap<Object, String> originalTexts = new IdentityHashMap<>();
        final IdentityHashMap<Object, int[]> minimumResizeSizes = new IdentityHashMap<>();
        SelectionOverlay overlay;
        boolean editing;
        boolean transformMode;
        boolean textTransformMode;
        Object transformTarget;

        State(View root, ViewGroup content, View editButton, View deleteButton,
                View editTextButton, View opacityButton) {
            this.root = root;
            this.content = content;
            this.editButton = editButton;
            this.deleteButton = deleteButton;
            this.editTextButton = editTextButton;
            this.opacityButton = opacityButton;
        }
    }

    private enum DragMode {
        NONE, NW, NE, SE, SW
    }

    private static final class SelectionOverlay extends View {
        private final State state;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float touchPadding;
        private final int touchSlop;

        private float downX;
        private float downY;
        private Object downTarget;
        private boolean pendingDoubleTap;
        private boolean exitDoubleTap;
        private boolean moved;
        private boolean dragging;
        private DragMode dragMode = DragMode.NONE;
        // 0 = not classified, 1 = font size, 2 = line spacing.
        private int textGestureMode;
        private Rect dragStart;
        private int[] rawStart;
        private float[] textTransformStart;

        // Whole-region movement is deliberately a two-finger gesture. This avoids
        // competing with the four single-finger corner resize targets on small panels.
        private boolean twoFingerMove;
        private boolean twoFingerTextMove;
        private float[] twoFingerTextStartTransform;
        private boolean twoFingerConsumed;
        private boolean twoFingerMoved;
        private int pointerIdA = -1;
        private int pointerIdB = -1;
        private float twoFingerStartCenterX;
        private float twoFingerStartCenterY;
        private Rect twoFingerStartBounds;
        private int[] twoFingerRawStart;

        private Object lastTapTarget;
        private float lastTapX;
        private float lastTapY;
        private long lastTapTime;

        SelectionOverlay(Context context, State state) {
            super(context);
            this.state = state;
            fill.setStyle(Paint.Style.FILL);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(dp(context, 2.0f));
            touchPadding = dp(context, 12.0f);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            handleFill.setStyle(Paint.Style.FILL);
            handleFill.setColor(Color.WHITE);
            handleOutline.setStyle(Paint.Style.STROKE);
            handleOutline.setStrokeWidth(dp(context, 2.0f));
            handleOutline.setColor(0xFF00A6D6);
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
                if ((state.transformMode || state.textTransformMode) && item == state.transformTarget) {
                    drawHandles(canvas, rect);
                }
            }
        }

        private void drawHandles(Canvas canvas, Rect rect) {
            // Scale the visible handles down for unusually thin balloons so the
            // four corner markers do not visually merge into a single strip.
            float half = Math.min(dp(getContext(), 5.5f),
                    Math.max(dp(getContext(), 1.5f),
                            Math.min(rect.width(), rect.height()) * 0.20f));
            float[][] points = new float[][] {
                    {rect.left, rect.top}, {rect.right, rect.top},
                    {rect.right, rect.bottom}, {rect.left, rect.bottom}
            };
            for (float[] point : points) {
                RectF handle = new RectF(
                        point[0] - half, point[1] - half,
                        point[0] + half, point[1] + half);
                canvas.drawRoundRect(handle, dp(getContext(), 2.0f), dp(getContext(), 2.0f), handleFill);
                canvas.drawRoundRect(handle, dp(getContext(), 2.0f), dp(getContext(), 2.0f), handleOutline);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!state.editing) return false;
            final int action = event.getActionMasked();
            final float x = event.getX();
            final float y = event.getY();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    downY = y;
                    moved = false;
                    downTarget = findTarget(x, y);
                    pendingDoubleTap = false;
                    exitDoubleTap = false;
                    dragging = false;
                    dragMode = DragMode.NONE;
                    textGestureMode = 0;
                    dragStart = null;
                    rawStart = null;
                    twoFingerMove = false;
                    twoFingerTextMove = false;
                    twoFingerTextStartTransform = null;
                    twoFingerConsumed = false;
                    twoFingerMoved = false;

                    if (state.textTransformMode) {
                        Rect activeBounds = readBounds(state.transformTarget);
                        if (activeBounds != null) {
                            dragStart = new Rect(activeBounds);
                            dragMode = findDragMode(x, y, activeBounds);
                            textTransformStart = getTextTransform(state.transformTarget);
                            dragging = true;
                            return true;
                        }
                        return true;
                    }

                    if (state.transformMode) {
                        Rect activeBounds = readBounds(state.transformTarget);
                        if (activeBounds != null && isDoubleTapCandidate(state.transformTarget, x, y)
                                && activeBounds.contains(Math.round(x), Math.round(y))) {
                            exitDoubleTap = true;
                            // A second pointer anywhere on the overlay turns this into
                            // a two-finger move; pointer-down takes precedence over the tap.
                            return true;
                        }

                        DragMode mode = activeBounds == null
                                ? DragMode.NONE : findDragMode(x, y, activeBounds);
                        if (mode != DragMode.NONE) {
                            dragMode = mode;
                            dragStart = new Rect(activeBounds);
                            rawStart = readRawBounds(state.transformTarget);
                            if (rawStart == null) {
                                dragMode = DragMode.NONE;
                                dragStart = null;
                            } else {
                                dragging = true;
                                return true;
                            }
                        }

                        // Do not leave transform mode on ACTION_DOWN outside the selected
                        // region. The user may be placing the first finger of a global
                        // two-finger drag. A single-finger tap is resolved later on ACTION_UP.
                        return true;
                    }

                    pendingDoubleTap = downTarget != null
                            && isDoubleTapCandidate(downTarget, x, y);
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (state.textTransformMode && event.getPointerCount() >= 2) {
                        beginTwoFingerTextMove(event);
                    } else if (state.transformMode && event.getPointerCount() >= 2) {
                        beginTwoFingerMove(event);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (twoFingerTextMove && event.getPointerCount() >= 2) {
                        updateTwoFingerTextMove(event);
                        return true;
                    }
                    if (twoFingerMove && event.getPointerCount() >= 2) {
                        updateTwoFingerMove(event);
                        return true;
                    }
                    if (distanceSquared(x, y, downX, downY) > touchSlop * touchSlop) {
                        moved = true;
                    }
                    if (state.textTransformMode && dragging && dragStart != null && moved) {
                        applyTextTransform(x, y);
                    } else if (state.transformMode && dragging && dragStart != null && rawStart != null && moved) {
                        applyCornerResize(x, y);
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    if (twoFingerTextMove) {
                        updateTwoFingerTextMove(event);
                        twoFingerTextMove = false;
                        twoFingerConsumed = true;
                        twoFingerTextStartTransform = null;
                        clearTwoFingerData();
                    } else if (twoFingerMove) {
                        updateTwoFingerMove(event);
                        twoFingerMove = false;
                        twoFingerConsumed = true;
                        clearTwoFingerData();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (twoFingerConsumed) {
                        twoFingerConsumed = false;
                        clearTwoFingerData();
                        clearDrag();
                        performClick();
                        return true;
                    }
                    if (state.textTransformMode && dragging) {
                        if (moved) applyTextTransform(x, y);
                        clearDrag();
                    } else if (state.transformMode && exitDoubleTap && !moved) {
                        exitTransformMode(state);
                    } else if (state.transformMode && dragging) {
                        if (moved) {
                            applyCornerResize(x, y);
                            Log.i(TAG, "Translated region corner geometry changed");
                        }
                        clearDrag();
                    } else if (state.transformMode) {
                        if (!moved && downTarget != null && downTarget != state.transformTarget) {
                            // A single-finger tap on another panel exits geometry mode
                            // and selects that panel. A second finger would already have
                            // intercepted the gesture in ACTION_POINTER_DOWN.
                            Object tappedTarget = downTarget;
                            exitTransformMode(state);
                            toggleAt(tappedTarget);
                            recordTap(tappedTarget, x, y);
                        } else if (!moved && downTarget == null) {
                            exitTransformMode(state);
                            clearTap();
                        } else if (!moved) {
                            recordTap(state.transformTarget, x, y);
                        }
                    } else if (!moved && downTarget != null && pendingDoubleTap) {
                        enterTransformMode(state, downTarget);
                    } else if (!moved && downTarget != null) {
                        toggleAt(downTarget);
                        recordTap(downTarget, x, y);
                    } else {
                        clearTap();
                    }
                    performClick();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    clearDrag();
                    clearTwoFingerData();
                    twoFingerTextMove = false;
                    twoFingerTextStartTransform = null;
                    twoFingerConsumed = false;
                    moved = false;
                    pendingDoubleTap = false;
                    exitDoubleTap = false;
                    return true;

                default:
                    return true;
            }
        }


        private void beginTwoFingerTextMove(MotionEvent event) {
            if (twoFingerTextMove || event.getPointerCount() < 2 || state.transformTarget == null) return;
            int secondIndex = event.getActionIndex();
            int firstIndex = secondIndex == 0 ? 1 : 0;
            if (firstIndex >= event.getPointerCount() || secondIndex >= event.getPointerCount()) return;
            float[] transform = getTextTransform(state.transformTarget);
            if (transform == null || transform.length < 3) return;
            pointerIdA = event.getPointerId(firstIndex);
            pointerIdB = event.getPointerId(secondIndex);
            twoFingerStartCenterX = (event.getX(firstIndex) + event.getX(secondIndex)) / 2.0f;
            twoFingerStartCenterY = (event.getY(firstIndex) + event.getY(secondIndex)) / 2.0f;
            twoFingerTextStartTransform = transform;
            twoFingerTextMove = true;
            twoFingerConsumed = true;
            twoFingerMoved = false;
            dragging = false;
            dragMode = DragMode.NONE;
            textGestureMode = 0;
            clearTap();
            Log.i(TAG, "Two-finger text movement started");
        }

        private void updateTwoFingerTextMove(MotionEvent event) {
            if (!twoFingerTextMove || twoFingerTextStartTransform == null) return;
            int indexA = event.findPointerIndex(pointerIdA);
            int indexB = event.findPointerIndex(pointerIdB);
            if (indexA < 0 || indexB < 0) return;
            float centerX = (event.getX(indexA) + event.getX(indexB)) / 2.0f;
            float centerY = (event.getY(indexA) + event.getY(indexB)) / 2.0f;
            if (distanceSquared(centerX, centerY, twoFingerStartCenterX, twoFingerStartCenterY)
                    > touchSlop * touchSlop) twoFingerMoved = true;
            if (!twoFingerMoved) return;
            float[] start = twoFingerTextStartTransform;
            float lineSpacing = start.length > 3 ? start[3] : 1.0f;
            setTextTransform(state.transformTarget,
                    start[0] + centerX - twoFingerStartCenterX,
                    start[1] + centerY - twoFingerStartCenterY,
                    start[2], lineSpacing);
            refreshRenderers(state);
        }

        private void beginTwoFingerMove(MotionEvent event) {
            if (twoFingerMove || event.getPointerCount() < 2 || state.transformTarget == null) return;
            Rect bounds = readBounds(state.transformTarget);
            if (bounds == null) return;

            int secondIndex = event.getActionIndex();
            int firstIndex = secondIndex == 0 ? 1 : 0;
            if (firstIndex >= event.getPointerCount() || secondIndex >= event.getPointerCount()) return;

            // The two pointers are deliberately accepted anywhere inside this
            // full-screen overlay, not merely within the small selected rectangle.
            // Their midpoint controls translation, so neither corner handles nor
            // neighbouring panels can steal the gesture.
            float ax = event.getX(firstIndex);
            float ay = event.getY(firstIndex);
            float bx = event.getX(secondIndex);
            float by = event.getY(secondIndex);

            int[] geometry = readRawBounds(state.transformTarget);
            if (geometry == null) return;

            pointerIdA = event.getPointerId(firstIndex);
            pointerIdB = event.getPointerId(secondIndex);
            twoFingerStartCenterX = (ax + bx) / 2.0f;
            twoFingerStartCenterY = (ay + by) / 2.0f;
            twoFingerStartBounds = new Rect(bounds);
            twoFingerRawStart = geometry;
            twoFingerMove = true;
            twoFingerConsumed = true;
            twoFingerMoved = false;
            dragging = false;
            dragMode = DragMode.NONE;
            exitDoubleTap = false;
            clearTap();
            Log.i(TAG, "Two-finger region move started");
        }

        private void updateTwoFingerMove(MotionEvent event) {
            if (!twoFingerMove || twoFingerStartBounds == null || twoFingerRawStart == null) return;
            int indexA = event.findPointerIndex(pointerIdA);
            int indexB = event.findPointerIndex(pointerIdB);
            if (indexA < 0 || indexB < 0) return;

            float centerX = (event.getX(indexA) + event.getX(indexB)) / 2.0f;
            float centerY = (event.getY(indexA) + event.getY(indexB)) / 2.0f;
            if (distanceSquared(centerX, centerY,
                    twoFingerStartCenterX, twoFingerStartCenterY) > touchSlop * touchSlop) {
                twoFingerMoved = true;
            }
            if (!twoFingerMoved) return;

            int dx = Math.round(centerX - twoFingerStartCenterX);
            int dy = Math.round(centerY - twoFingerStartCenterY);
            Rect updated = new Rect(twoFingerStartBounds);
            int maxX = Math.max(getWidth(), updated.right);
            int maxY = Math.max(getHeight(), updated.bottom);
            int left = clamp(updated.left + dx, 0, Math.max(0, maxX - updated.width()));
            int top = clamp(updated.top + dy, 0, Math.max(0, maxY - updated.height()));
            updated.set(left, top, left + twoFingerStartBounds.width(),
                    top + twoFingerStartBounds.height());
            writeGeometry(state.transformTarget, twoFingerStartBounds, twoFingerRawStart, updated);
            refreshRenderers(state);
        }

        private void clearTwoFingerData() {
            pointerIdA = -1;
            pointerIdB = -1;
            twoFingerStartBounds = null;
            twoFingerRawStart = null;
            twoFingerMoved = false;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        private void applyTextTransform(float x, float y) {
            if (state.transformTarget == null || dragStart == null || textTransformStart == null) return;
            float dx = x - downX;
            float dy = y - downY;
            float nextX = textTransformStart[0];
            float nextY = textTransformStart[1];
            float nextScale = textTransformStart[2];
            float startLineSpacing = textTransformStart.length > 3
                    ? textTransformStart[3] : 1.0f;
            float nextLineSpacing = startLineSpacing;

            // One finger changes typography only. Text translation is two-finger.
            float directionGate = Math.max(dp(getContext(), 16.0f), touchSlop * 1.5f);
            if (textGestureMode == 0
                    && Math.max(Math.abs(dx), Math.abs(dy)) >= directionGate) {
                if (Math.abs(dx) >= Math.abs(dy) * 1.25f) textGestureMode = 1;
                else if (Math.abs(dy) >= Math.abs(dx) * 1.25f) textGestureMode = 2;
                else textGestureMode = 3;
            }
            if (textGestureMode == 3 || textGestureMode == 0) return;
            if (textGestureMode == 2) {
                final float minLineSpacing = 0.85f;
                final float maxLineSpacing = 3.0f;
                float availableRange = dy >= 0f
                        ? maxLineSpacing - startLineSpacing
                        : startLineSpacing - minLineSpacing;
                float sensitivity = Math.max(dp(getContext(), 72.0f),
                        Math.min(dp(getContext(), 120.0f), dragStart.height() * 0.80f));
                nextLineSpacing = Math.max(minLineSpacing, Math.min(maxLineSpacing,
                        startLineSpacing + (dy / sensitivity) * availableRange));
            } else {
                final float minScale = 0.25f;
                final float maxScale = 2.50f;
                float outward = (dragMode == DragMode.NW || dragMode == DragMode.SW)
                        ? -dx : dx;
                float availableRange = outward >= 0f
                        ? maxScale - textTransformStart[2]
                        : textTransformStart[2] - minScale;
                float sensitivity = Math.max(dp(getContext(), 80.0f),
                        Math.min(dp(getContext(), 160.0f), dragStart.width() * 0.80f));
                nextScale = Math.max(minScale, Math.min(maxScale,
                        textTransformStart[2] + (outward / sensitivity) * availableRange));
            }
            setTextTransform(state.transformTarget, nextX, nextY, nextScale, nextLineSpacing);
            refreshRenderers(state);
        }
        private void applyCornerResize(float x, float y) {
            if (state.transformTarget == null || dragStart == null || rawStart == null) return;
            int dx = Math.round(x - downX);
            int dy = Math.round(y - downY);
            Rect updated = new Rect(dragStart);
            int maxX = Math.max(getWidth(), dragStart.right);
            int maxY = Math.max(getHeight(), dragStart.bottom);
            int[] minimum = state.minimumResizeSizes.get(state.transformTarget);
            int minWidth = minimum != null ? minimum[0]
                    : Math.max(1, Math.round(dp(getContext(), 36.0f)));
            int minHeight = minimum != null ? minimum[1]
                    : Math.max(1, Math.round(dp(getContext(), 28.0f)));

            switch (dragMode) {
                case NW:
                    updated.left = clamp(dragStart.left + dx, 0, dragStart.right - minWidth);
                    updated.top = clamp(dragStart.top + dy, 0, dragStart.bottom - minHeight);
                    break;
                case NE:
                    updated.right = clamp(dragStart.right + dx, dragStart.left + minWidth, maxX);
                    updated.top = clamp(dragStart.top + dy, 0, dragStart.bottom - minHeight);
                    break;
                case SE:
                    updated.right = clamp(dragStart.right + dx, dragStart.left + minWidth, maxX);
                    updated.bottom = clamp(dragStart.bottom + dy, dragStart.top + minHeight, maxY);
                    break;
                case SW:
                    updated.left = clamp(dragStart.left + dx, 0, dragStart.right - minWidth);
                    updated.bottom = clamp(dragStart.bottom + dy, dragStart.top + minHeight, maxY);
                    break;
                default:
                    return;
            }
            writeGeometry(state.transformTarget, dragStart, rawStart, updated);
            refreshRenderers(state);
        }

        private DragMode findDragMode(float x, float y, Rect bounds) {
            // Pick the nearest horizontal and vertical edge first. This assigns
            // every touch to one corner quadrant and prevents the top/bottom
            // targets from collapsing into each other on very short panels.
            boolean left = Math.abs(x - bounds.left) <= Math.abs(x - bounds.right);
            boolean top = Math.abs(y - bounds.top) <= Math.abs(y - bounds.bottom);
            float cornerX = left ? bounds.left : bounds.right;
            float cornerY = top ? bounds.top : bounds.bottom;

            // Keep each target within its half of the panel. The previous fixed
            // 7 dp minimum could make opposite handles overlap on thin balloons.
            float radiusX = Math.max(1.0f, Math.min(dp(getContext(), 16.0f), bounds.width() * 0.49f));
            float radiusY = Math.max(1.0f, Math.min(dp(getContext(), 16.0f), bounds.height() * 0.49f));
            float dx = (x - cornerX) / radiusX;
            float dy = (y - cornerY) / radiusY;
            if (dx * dx + dy * dy > 1.0f) return DragMode.NONE;

            if (left && top) return DragMode.NW;
            if (!left && top) return DragMode.NE;
            if (!left) return DragMode.SE;
            return DragMode.SW;
        }

        private Object findTarget(float x, float y) {
            List<?> regions = readList(state.root);
            if (regions == null || regions.isEmpty()) return null;
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
            return best;
        }

        private void toggleAt(Object item) {
            if (item == null) return;
            if (state.selected.containsKey(item)) state.selected.remove(item);
            else state.selected.put(item, Boolean.TRUE);
            updateSelectionButtons(state);
            invalidate();
        }

        private boolean isDoubleTapCandidate(Object item, float x, float y) {
            if (item == null || item != lastTapTarget || lastTapTime == 0L) return false;
            long elapsed = SystemClock.uptimeMillis() - lastTapTime;
            float distance = dp(getContext(), 48.0f);
            return elapsed >= 0L && elapsed <= ViewConfiguration.getDoubleTapTimeout()
                    && distanceSquared(x, y, lastTapX, lastTapY) <= distance * distance;
        }

        private void recordTap(Object item, float x, float y) {
            lastTapTarget = item;
            lastTapX = x;
            lastTapY = y;
            lastTapTime = SystemClock.uptimeMillis();
        }

        private void clearTap() {
            lastTapTarget = null;
            lastTapTime = 0L;
        }

        private void clearDrag() {
            dragging = false;
            dragMode = DragMode.NONE;
            textGestureMode = 0;
            dragStart = null;
            rawStart = null;
            textTransformStart = null;
            exitDoubleTap = false;
        }

        private static float distanceSquared(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2;
            float dy = y1 - y2;
            return dx * dx + dy * dy;
        }
    }
}
