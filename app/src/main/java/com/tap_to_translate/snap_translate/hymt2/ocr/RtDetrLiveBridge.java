package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Point;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live bridge from the host's existing vh.j OCR coordinator to RT-DETR.
 *
 * The original ML Kit call still runs on the full screenshot. When its result reaches
 * vh.j.d(), this bridge keeps only OCR blocks that overlap a model-detected comic text
 * region. If model inference fails, times out, or yields no usable matches, the original
 * OCR result is left untouched.
 */
public final class RtDetrLiveBridge {
    private static final String TAG = "TapTranslateRTDETR";
    private static final Object LOCK = new Object();
    // One active screen-translation request at a time. Tokens flow from the exact
    // screenshot Bitmap to the resulting OCR List and finally to the Hy-MT2 host.
    private static final AtomicLong REQUEST_GENERATION = new AtomicLong(0L);
    private static final Map<Bitmap, Long> FRAME_GENERATIONS =
            Collections.synchronizedMap(new WeakHashMap<Bitmap, Long>());
    private static final Map<Object, Long> RESULT_GENERATIONS =
            Collections.synchronizedMap(new IdentityHashMap<Object, Long>());
    private static final Map<Object, PendingFrame> PENDING =
            Collections.synchronizedMap(new WeakHashMap<Object, PendingFrame>());
    private static final Map<Object, List<DetectedRegion>> ACTIVE_REGIONS =
            Collections.synchronizedMap(new WeakHashMap<Object, List<DetectedRegion>>());
    private static final Map<Object, Boolean> OVERLAY_IS_BUBBLE =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final Map<Object, Boolean> OVERLAY_IS_FREE_TEXT =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    // Manual editor override. Weak keys avoid retaining deleted overlay models.
    private static final Map<Object, Boolean> FORCED_FREE_TEXT =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final Map<Object, DetectedRegion> OVERLAY_BUBBLE_MASKS =
            Collections.synchronizedMap(new WeakHashMap<Object, DetectedRegion>());
    // Reuse one cropped mask while the overlay remains attached to the same OCR frame/bubble.
    private static final Map<Object, List<DetectedRegion>> OVERLAY_MASK_SOURCES =
            Collections.synchronizedMap(new WeakHashMap<Object, List<DetectedRegion>>());
    private static final Map<Object, String> OVERLAY_MASK_KEYS =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final Map<Object, DetectedRegion> OVERLAY_FREE_EXCLUSION_MASKS =
            Collections.synchronizedMap(new WeakHashMap<Object, DetectedRegion>());
    private static final Map<Object, float[]> BUBBLE_TEXT_TRANSFORMS =
            Collections.synchronizedMap(new WeakHashMap<Object, float[]>());
    private static final Map<Object, Integer> FREE_TEXT_ALPHA_OVERRIDES =
            Collections.synchronizedMap(new WeakHashMap<Object, Integer>());
    private static final String BUBBLE_AUTO_WRAP = "ocr_bubblepop_auto_wrap";
    private static final String OCR_PREFS = "taptranslate_ocr_settings";
    private static final String FREE_ALPHA_ENABLED = "ocr_free_text_alpha_enabled";
    private static final String FREE_ALPHA_PERCENT = "ocr_free_text_alpha_percent";
    private static final String PADDLE_ENGINE_NAME = "PP-OCRv6 (Comics)";
    private static final String BUBBLEPOP_PAGE_MASK_ENABLED = "ocr_bubblepop_page_mask_enabled";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "taptranslate-rtdetr");
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static volatile RtDetrComicRegionDetector detector;
    private static volatile Context detectorContext;
    private static volatile BubblePopPageMaskDetector pageMaskDetector;
    private static volatile Context pageMaskContext;

    private RtDetrLiveBridge() {}

    /** Invalidates in-flight OCR and translation work when the host double-tap cancel is used. */
    public static void cancelActiveRequest() {
        long generation;
        synchronized (LOCK) {
            generation = REQUEST_GENERATION.incrementAndGet();
            for (PendingFrame pending : PENDING.values()) {
                if (pending != null && !pending.task.isDone()) pending.task.cancel(true);
            }
            PENDING.clear();
            ACTIVE_REGIONS.clear();
        }
        Log.i(TAG, "Active screen translation cancelled; generation=" + generation);
    }

    /** Called from vh/j.l before an OCR result is handed to the host service. */
    public static void acceptOcrResult(Object owner, Bitmap source, List<?> result) {
        if (source == null || result == null) return;
        Long generation = FRAME_GENERATIONS.get(source);
        if (generation == null) return; // Preserve non-hooked/manual OCR paths.

        // Tag the exact OCR result for service-side rejection if it became stale.
        // Never abort vh/j.l here: its finally path calls g(boolean) to release
        // the OCR pipeline state and must run for every callback.
        RESULT_GENERATIONS.put(result, generation);
        long activeGeneration = REQUEST_GENERATION.get();
        if (generation.longValue() != activeGeneration) {
            Log.i(TAG, "Marking stale OCR result for service-side discard; frameGeneration="
                    + generation + " activeGeneration=" + activeGeneration);
        }
    }

    /** Early guard in ScreenTranslateService.c(List), before changing the visible overlay. */
    public static boolean isOcrResultCurrent(Object result) {
        if (result == null) return true;
        Long generation = RESULT_GENERATIONS.remove(result);
        if (generation == null) return true;
        boolean current = generation.longValue() == REQUEST_GENERATION.get();
        if (!current) {
            Log.i(TAG, "Discarding stale OCR list before Hy-MT2 dispatch; frameGeneration="
                    + generation + " activeGeneration=" + REQUEST_GENERATION.get());
        }
        return current;
    }

    private static boolean isFrameCurrent(Bitmap source) {
        if (source == null) return false;
        Long generation = FRAME_GENERATIONS.get(source);
        return generation == null || generation.longValue() == REQUEST_GENERATION.get();
    }

    /** Called from the real vh.j.j(Bitmap) entry point before the existing OCR starts. */
    public static void onFrame(final Object owner, final Bitmap source) {
        if (owner == null || source == null || source.isRecycled()) return;

        // A new screenshot supersedes every older OCR/translation request, even when
        // optional RT-DETR/Paddle features are disabled for this capture.
        synchronized (LOCK) {
            PendingFrame previous = PENDING.get(owner);
            if (previous != null && previous.source == source && !previous.task.isDone()) return;
            long generation = REQUEST_GENERATION.incrementAndGet();
            FRAME_GENERATIONS.put(source, Long.valueOf(generation));
        }

        final Context context = resolveContext(owner);
        if (context == null) {
            Log.w(TAG, "RT-DETR skipped: host OCR context unavailable");
            return;
        }

        final boolean paddleEnabled = isPaddleSelected();
        final boolean classifyForFreeTextOpacity = isSeparateFreeTextAlphaEnabled(context);
        final boolean pageMaskEnabled = isBubblePopPageMaskEnabled(context);
        if (!paddleEnabled && !classifyForFreeTextOpacity && !pageMaskEnabled) {
            synchronized (LOCK) {
                PendingFrame previous = PENDING.remove(owner);
                if (previous != null && !previous.task.isDone()) previous.task.cancel(true);
                ACTIVE_REGIONS.remove(owner);
            }
            return;
        }

        synchronized (LOCK) {
            PendingFrame previous = PENDING.get(owner);
            if (previous != null && previous.source == source && !previous.task.isDone()) {
                return;
            }
            ACTIVE_REGIONS.remove(owner);
            if (previous != null && !previous.task.isDone()) {
                previous.task.cancel(true);
            }

            final Bitmap snapshot;
            try {
                snapshot = source.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable error) {
                Log.w(TAG, "RT-DETR screenshot copy failed; keeping original OCR", error);
                return;
            }
            if (snapshot == null) return;

            FutureTask<List<DetectedRegion>> task = new FutureTask<>(
                    () -> {
                        ArrayList<DetectedRegion> combined = new ArrayList<>();
                        try {
                            try {
                                RtDetrComicRegionDetector active = getDetector(context);
                                OcrFrame frame = new OcrFrame(
                                        snapshot,
                                        new Rect(0, 0, snapshot.getWidth(), snapshot.getHeight()),
                                        0, 0, 1f, 1f);
                                List<DetectedRegion> detected = active.detect(frame);
                                if (detected != null) combined.addAll(detected);
                                Log.i(TAG, "RT-DETR completed: regions="
                                        + (detected == null ? 0 : detected.size()));
                            } catch (Throwable detectorError) {
                                Log.e(TAG, "RT-DETR failed; continuing with independent BubblePop pass",
                                        detectorError);
                            }

                            if (isBubblePopPageMaskEnabled(context)) {
                                try {
                                    DetectedRegion pageMask = getPageMaskDetector(context).detect(snapshot);
                                    if (pageMask != null) {
                                        combined.add(pageMask);
                                        Log.i(TAG, "BubblePop page mask attached: "
                                                + pageMask.getMaskWidth() + "x" + pageMask.getMaskHeight());
                                    } else {
                                        Log.w(TAG, "BubblePop returned no usable page mask");
                                    }
                                } catch (Throwable maskError) {
                                    Log.e(TAG, "BubblePop failed independently; RT-DETR regions retained",
                                            maskError);
                                }
                            }

                            int containers = 0;
                            int bubbleText = 0;
                            int free = 0;
                            for (DetectedRegion region : combined) {
                                if (region.getKind() == OcrRegionKind.BUBBLE_CONTAINER) containers++;
                                else if (region.getKind() == OcrRegionKind.SPEECH_BUBBLE) bubbleText++;
                                else if (region.getKind() == OcrRegionKind.FREE_TEXT) free++;
                            }
                            Log.i(TAG, "region pass complete: total=" + combined.size()
                                    + " bubble_containers=" + containers
                                    + " text_bubble=" + bubbleText + " text_free=" + free
                                    + " frame=" + snapshot.getWidth() + "x" + snapshot.getHeight());
                            return combined;
                        } finally {
                            if (!snapshot.isRecycled()) snapshot.recycle();
                        }
                    });

            PENDING.put(owner, new PendingFrame(source, task));
            EXECUTOR.execute(task);
        }
    }

    /**
     * Called from vh.j.d(Context, nf.d, Bitmap, boolean), after ML Kit produced its
     * normal result but before the host groups it into translation overlays.
     */
    public static void filter(
            Object owner, Context hostContext, Object mlKitResult, Bitmap source) {
        if (owner == null || mlKitResult == null || source == null) {
            Log.w(TAG, "hybrid OCR skipped: null owner/result/source");
            return;
        }
        if (!isFrameCurrent(source)) {
            Log.i(TAG, "Stale OCR callback ignored before region/Paddle processing");
            return;
        }

        final Context resolvedContext = resolveContext(owner);
        final Context context = resolvedContext != null ? resolvedContext : hostContext;
        final boolean paddleEnabled = isPaddleSelected();
        final boolean freeAlphaEnabled = isSeparateFreeTextAlphaEnabled(context);
        final boolean pageMaskEnabled = isBubblePopPageMaskEnabled(context);
        if (!paddleEnabled && !freeAlphaEnabled && !pageMaskEnabled) {
            synchronized (LOCK) { ACTIVE_REGIONS.remove(owner); }
            return;
        }

        // OCR must not depend on RT-DETR/BubblePop succeeding. A missing frame,
        // bitmap mismatch, detector exception or timeout disables only region
        // association; PP-OCR still gets the actual screenshot and can recognize
        // text as unmatched/free blocks. Never treat those blocks as bubble masks.
        List<DetectedRegion> regions = Collections.emptyList();
        final PendingFrame pending;
        synchronized (LOCK) { pending = PENDING.get(owner); }
        if (pending == null) {
            Log.w(TAG, "No pending RT-DETR frame; continuing OCR without region association");
        } else if (pending.source != source) {
            Log.w(TAG, "RT-DETR bitmap identity mismatch; continuing OCR without region association");
            removeIfCurrent(owner, pending);
        } else {
            try {
                List<DetectedRegion> detected = pending.task.get(20, TimeUnit.SECONDS);
                if (detected != null) regions = detected;
            } catch (Throwable error) {
                Log.w(TAG, "RT-DETR/BubblePop unavailable; continuing OCR without region association", error);
            } finally {
                removeIfCurrent(owner, pending);
            }
        }

        if (!isFrameCurrent(source)) {
            Log.i(TAG, "Cancelled/stale frame discarded after RT-DETR wait");
            return;
        }
        synchronized (LOCK) {
            ACTIVE_REGIONS.put(owner, new ArrayList<>(regions));
        }
        Log.i(TAG, "region association ready: regions=" + regions.size()
                + " paddle=" + paddleEnabled + " bubblepop=" + pageMaskEnabled);

        if (!paddleEnabled) return;

        if (!isFrameCurrent(source)) {
            Log.i(TAG, "Cancelled/stale frame discarded before Paddle OCR");
            return;
        }
        try {
            List<PaddleOcrTextGroup> paddleGroups = PaddleOcrLiveBridge.recognizeBlocking(
                    context, source, regions, 20000L);
            if (!isFrameCurrent(source)) {
                Log.i(TAG, "Discarding Paddle OCR output for a cancelled/stale frame");
                return;
            }
            if (paddleGroups != null && !paddleGroups.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object> blocks = (List<Object>) mlKitResult.getClass().getField("a").get(mlKitResult);
                int originalCount = blocks.size();
                int created = applyPaddleBubbleGroups(blocks, paddleGroups, regions);
                Log.i(TAG, "Paddle host handoff completed: groups=" + paddleGroups.size()
                        + " native_blocks=" + created
                        + " original_mlkit_blocks=" + originalCount
                        + " region_association=" + (!regions.isEmpty())
                        + " mlkit_fallback=" + (created == 0));
            } else {
                Log.w(TAG, "PP-OCRv6 returned no groups; ML Kit result retained");
            }
        } catch (Throwable error) {
            Log.e(TAG, "PP-OCRv6 handoff failed; ML Kit output retained", error);
        }
    }


    /**
     * Replace only ML Kit speech-bubble blocks with Paddle utterances. Existing ML Kit
     * child word boxes are reused for the host's geometry/colour pipeline. Free text
     * remains untouched in this stage, so signage/SFX cannot be silently discarded.
     */
    /**
     * Build native host OCR blocks from Paddle geometry. Bubble text must not depend
     * on ML Kit having detected the same words: PP-OCRv6 supplies both parent and
     * per-line child rectangles. Existing ML Kit blocks are retained only when they
     * belong to free/unknown text outside RT-DETR speech-bubble containers.
     */
    /**
     * Build host OCR bubble blocks from Paddle's own text and line geometry.
     * ML Kit is not required to detect the same phrase. Existing ML Kit blocks are
     * retained only for bubbles where Paddle did not produce a replacement and
     * for independent free/unknown text.
     */
    /**
     * Build host OCR blocks from Paddle's own text and line geometry.
     * Bubble and free-text blocks both originate from Paddle. ML Kit blocks are
     * retained only where Paddle produced no corresponding text.
     */
    private static int applyPaddleBubbleGroups(
            List<Object> blocks,
            List<PaddleOcrTextGroup> groups,
            List<DetectedRegion> regions) {
        try {
            ArrayList<PaddleOcrTextGroup> paddleGroups = new ArrayList<>();
            java.util.HashSet<String> paddleBubbleIds = new java.util.HashSet<>();
            int paddleBubbleGroups = 0;
            int paddleFreeGroups = 0;
            for (PaddleOcrTextGroup group : groups) {
                if (group == null || group.getText() == null
                        || group.getText().trim().isEmpty()) continue;
                paddleGroups.add(group);
                if (group.getBubbleId() != null) {
                    paddleBubbleIds.add(group.getBubbleId());
                    paddleBubbleGroups++;
                } else {
                    paddleFreeGroups++;
                }
            }
            if (paddleGroups.isEmpty()) return 0;

            // nf/b is the host's child text-line class. Its constructor is injected
            // by merge_apk.py and delegates to the existing gf/j geometry constructor.
            Class<?> childClass = Class.forName("nf.b");
            Constructor<?> childConstructor = childClass.getDeclaredConstructor(
                    String.class, Rect.class, List.class, String.class);
            childConstructor.setAccessible(true);

            Class<?> parentClass = Class.forName("nf.c");
            Constructor<?> parentConstructor = parentClass.getDeclaredConstructor(
                    String.class, Rect.class, List.class, String.class,
                    java.util.AbstractList.class);
            parentConstructor.setAccessible(true);

            ArrayList<Object> paddleBlocks = new ArrayList<>();
            int generated = 0;
            for (PaddleOcrTextGroup group : paddleGroups) {
                ArrayList<PaddleOcrTextLine> lines = new ArrayList<>(group.getLines());
                if (lines.isEmpty()) {
                    lines.add(new PaddleOcrTextLine(
                            group.getText(), group.getBounds(), group.getConfidence()));
                }
                ArrayList<Object> children = new ArrayList<>();
                for (PaddleOcrTextLine line : lines) {
                    if (line == null || line.getText() == null
                            || line.getText().trim().isEmpty()) continue;
                    Rect lineBounds = line.getBounds();
                    if (lineBounds == null || lineBounds.width() <= 0
                            || lineBounds.height() <= 0) continue;
                    Object child = childConstructor.newInstance(
                            line.getText(), lineBounds, rectanglePolygon(lineBounds), "en");
                    children.add(child);
                }
                if (children.isEmpty()) {
                    Log.w(TAG, "Paddle group skipped: no valid child line geometry; bubble="
                            + group.getBubbleId() + " text=" + group.getText());
                    continue;
                }

                Rect bounds = group.getBounds();
                Object parent = parentConstructor.newInstance(
                        group.getText(), bounds, rectanglePolygon(bounds), "en", children);
                paddleBlocks.add(parent);
                generated++;
                Log.i(TAG, "Paddle native OCR block created: type="
                        + (group.getBubbleId() == null ? "FREE_TEXT" : "BUBBLE")
                        + " id=" + group.getBubbleId()
                        + " lines=" + children.size()
                        + " bounds=" + bounds.flattenToString()
                        + " text=" + group.getText());
            }
            if (generated == 0) return 0;

            // Remove an ML Kit block if Paddle represented that exact RT-DETR bubble,
            // or if its geometry overlaps a Paddle free-text line. Keep ML Kit blocks
            // for bubbles/areas which Paddle failed to recognize as a fallback.
            ArrayList<Object> retainedMlKit = new ArrayList<>();
            int replacedMlKitBlocks = 0;
            for (Object block : blocks) {
                Rect bounds = readBlockBounds(block);
                if (bounds == null) {
                    retainedMlKit.add(block);
                    continue;
                }
                String bubbleId = findBubbleContainerId(bounds, regions);
                boolean representedByPaddle = bubbleId != null
                        && paddleBubbleIds.contains(bubbleId);
                if (!representedByPaddle) {
                    for (PaddleOcrTextGroup group : paddleGroups) {
                        Rect paddleBounds = group.getBounds();
                        Rect intersection = new Rect(bounds);
                        if (!intersection.intersect(paddleBounds)) continue;
                        long inter = (long) intersection.width() * intersection.height();
                        long smaller = Math.min(
                                (long) bounds.width() * bounds.height(),
                                (long) paddleBounds.width() * paddleBounds.height());
                        if (smaller > 0L && (double) inter / smaller >= 0.18d) {
                            representedByPaddle = true;
                            break;
                        }
                    }
                }
                if (representedByPaddle) replacedMlKitBlocks++;
                else retainedMlKit.add(block);
            }

            ArrayList<Object> replacement = new ArrayList<>(paddleBlocks);
            replacement.addAll(retainedMlKit);
            replacement.sort((left, right) -> {
                Rect a = readBlockBounds(left);
                Rect b = readBlockBounds(right);
                if (a == null) return b == null ? 0 : 1;
                if (b == null) return -1;
                int byTop = Integer.compare(a.top, b.top);
                return byTop != 0 ? byTop : Integer.compare(a.left, b.left);
            });
            blocks.clear();
            blocks.addAll(replacement);
            Log.i(TAG, "Paddle host handoff: paddle_bubble_groups=" + paddleBubbleGroups
                    + " paddle_free_text_groups=" + paddleFreeGroups
                    + " native_blocks_created=" + generated
                    + " mlkit_blocks_replaced=" + replacedMlKitBlocks
                    + " mlkit_fallback_blocks_preserved=" + retainedMlKit.size()
                    + " mlkit_required_for_paddle_text=false");
            return generated;
        } catch (Throwable error) {
            Log.w(TAG, "Paddle native block conversion failed; original ML Kit result retained", error);
            return 0;
        }
    }

    private static ArrayList<Point> rectanglePolygon(Rect bounds) {
        ArrayList<Point> polygon = new ArrayList<>();
        polygon.add(new Point(bounds.left, bounds.top));
        polygon.add(new Point(bounds.right, bounds.top));
        polygon.add(new Point(bounds.right, bounds.bottom));
        polygon.add(new Point(bounds.left, bounds.bottom));
        return polygon;
    }

    private static Context resolveContext(Object owner) {
        try {
            Field field = owner.getClass().getField("a");
            Object value = field.get(owner);
            if (!(value instanceof Context)) return null;
            Context context = ((Context) value).getApplicationContext();
            return context == null ? (Context) value : context;
        } catch (Throwable error) {
            Log.w(TAG, "Cannot resolve OCR service context", error);
            return null;
        }
    }

    private static boolean isBubblePopPageMaskEnabled(Context context) {
        if (context == null) return false;
        try {
            return context.getSharedPreferences(OCR_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(BUBBLEPOP_PAGE_MASK_ENABLED, false);
        } catch (Throwable error) {
            Log.w(TAG, "Could not read BubblePop page-mask preference; feature disabled", error);
            return false;
        }
    }

    private static BubblePopPageMaskDetector getPageMaskDetector(Context context) throws Exception {
        BubblePopPageMaskDetector current = pageMaskDetector;
        if (current != null && pageMaskContext == context) return current;
        synchronized (RtDetrLiveBridge.class) {
            current = pageMaskDetector;
            if (current != null && pageMaskContext == context) return current;
            if (current != null) {
                try { current.close(); } catch (Throwable ignored) {}
            }
            current = new BubblePopPageMaskDetector(context);
            pageMaskContext = context;
            pageMaskDetector = current;
            Log.i(TAG, "BubblePop page-mask model/runtime initialized");
            return current;
        }
    }

    private static RtDetrComicRegionDetector getDetector(Context context) throws Exception {
        RtDetrComicRegionDetector current = detector;
        if (current != null && detectorContext == context) return current;
        synchronized (RtDetrLiveBridge.class) {
            current = detector;
            if (current != null && detectorContext == context) return current;
            if (current != null) {
                try { current.close(); } catch (Throwable ignored) {}
            }
            current = new RtDetrComicRegionDetector(
                    new OnnxRuntimeRtDetrInference(RtDetrModelProvider.prepare(context)));
            detectorContext = context;
            detector = current;
            Log.i(TAG, "RT-DETR model/runtime initialized");
            return current;
        }
    }

    /** Run a real RT-DETR inference with the cached model used by the live OCR path. */
    public static String diagnosticRtDetr(Context context, Bitmap bitmap) throws Exception {
        if (context == null || bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("RT-DETR diagnostic context/image unavailable");
        }
        RtDetrComicRegionDetector active = getDetector(context.getApplicationContext());
        OcrFrame frame = new OcrFrame(bitmap, new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), 0, 0, 1f, 1f);
        List<DetectedRegion> regions = active.detect(frame);
        int containers = 0;
        int textRegions = 0;
        for (DetectedRegion region : regions) {
            if (region.getKind() == OcrRegionKind.BUBBLE_CONTAINER) containers++; else textRegions++;
        }
        return "PASS: ONNX-модель загружена, инференс выполнен; областей=" + regions.size()
                + "; bubble_containers=" + containers + "; text_regions=" + textRegions
                + "; image=" + bitmap.getWidth() + "x" + bitmap.getHeight();
    }

    /** Run a real BubblePop segmentation inference with the cached model. */
    public static String diagnosticBubblePop(Context context, Bitmap bitmap) throws Exception {
        if (context == null || bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("BubblePop diagnostic context/image unavailable");
        }
        return getPageMaskDetector(context.getApplicationContext()).diagnostic(bitmap);
    }

    private static Rect readBlockBounds(Object block) {
        if (block == null) return null;
        try {
            Field field = block.getClass().getField("c");
            Object value = field.get(block);
            return value instanceof Rect ? new Rect((Rect) value) : null;
        } catch (Throwable error) {
            return null;
        }
    }

    private static boolean overlapsDetectedText(Rect text, List<DetectedRegion> regions) {
        long textArea = (long) text.width() * text.height();
        if (textArea <= 0L) return false;
        for (DetectedRegion region : regions) {
            if (region.getKind() == OcrRegionKind.BUBBLE_CONTAINER) continue;
            Rect detected = region.getBounds();
            Rect intersection = new Rect(text);
            if (!intersection.intersect(detected)) continue;
            long intersectionArea = (long) intersection.width() * intersection.height();
            long detectedArea = (long) detected.width() * detected.height();
            long smallerArea = Math.min(textArea, detectedArea);
            if (smallerArea > 0L && (double) intersectionArea / smallerArea >= 0.18d) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called by the host's pairwise text-block grouping loop. The legacy algorithm may
     * merge nearby OCR blocks into one paragraph; do not connect blocks assigned to
     * different RT-DETR speech-balloon contours. Unknown/free-text blocks keep legacy behavior.
     */
    public static boolean allowGrouping(
            Object owner, Object blockListValue, int firstIndex, int secondIndex) {
        try {
            List<?> blocks;
            if (blockListValue instanceof List<?>) {
                blocks = (List<?>) blockListValue;
            } else if (blockListValue != null) {
                // The live host passes nf/d, not its inner ArrayList directly.
                // nf/d.a contains the actual nf/c OCR blocks.
                Field listField = blockListValue.getClass().getField("a");
                Object listValue = listField.get(blockListValue);
                if (!(listValue instanceof List<?>)) return true;
                blocks = (List<?>) listValue;
            } else {
                return true;
            }
            if (firstIndex < 0 || secondIndex < 0
                    || firstIndex >= blocks.size() || secondIndex >= blocks.size()) return true;

            final List<DetectedRegion> regions;
            synchronized (LOCK) {
                regions = ACTIVE_REGIONS.get(owner);
            }
            if (regions == null || regions.isEmpty()) return true;

            Rect first = readBlockBounds(blocks.get(firstIndex));
            Rect second = readBlockBounds(blocks.get(secondIndex));
            if (first == null || second == null) return true;

            // Class 1 is text inside a bubble, not the bubble identity. A single
            // balloon may contain multiple text detections/ML Kit chunks. Associate
            // blocks only with class-0 bubble containers so chunks inside one balloon
            // remain eligible for the host's normal paragraph grouping.
            int firstContainer = findBubbleContainer(first, regions);
            int secondContainer = findBubbleContainer(second, regions);
            if (!BubbleRegionMatcher.canGroupSameRegion(firstContainer, secondContainer)) {
                Log.i(TAG, "prevented cross-region OCR grouping: blocks="
                        + firstIndex + "," + secondIndex
                        + " containers=" + firstContainer + "," + secondContainer);
                return false;
            }
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Bubble grouping guard failed; legacy grouping retained", error);
            return true;
        }
    }

    /**
     * Keeps every recognized overlay, but changes draw order so bubble text is painted
     * after free text. The host renderer (rh/f.onDraw) paints list entries in order,
     * so bubble groups must be moved to the tail of the list.
     */
    public static void prioritizeBubbleOverlays(Object owner, List<?> overlayGroups) {
        if (owner == null || overlayGroups == null || overlayGroups.isEmpty()) return;
        final List<DetectedRegion> regions;
        synchronized (LOCK) {
            regions = ACTIVE_REGIONS.get(owner);
        }
        if (regions == null || regions.isEmpty()) return;

        try {
            ArrayList<Object> underlays = new ArrayList<>();
            ArrayList<Object> bubbleOverlays = new ArrayList<>();
            int unreadable = 0;
            int bubbleMasksReady = 0;
            int bubbleMaskFallbacks = 0;
            for (Object group : overlayGroups) {
                Rect bounds = readOverlayBounds(group);
                if (bounds == null) {
                    unreadable++;
                    OVERLAY_IS_BUBBLE.put(group, Boolean.FALSE);
                    OVERLAY_IS_FREE_TEXT.put(group, Boolean.FALSE);
                    OVERLAY_BUBBLE_MASKS.remove(group);
                    OVERLAY_MASK_SOURCES.remove(group);
                    OVERLAY_MASK_KEYS.remove(group);
                    OVERLAY_FREE_EXCLUSION_MASKS.remove(group);
                    underlays.add(group);
                    continue;
                }

                double bubbleTextScore = bestTextRegionScore(
                        bounds, regions, OcrRegionKind.SPEECH_BUBBLE);
                double freeTextScore = bestTextRegionScore(
                        bounds, regions, OcrRegionKind.FREE_TEXT);

                // Direct class-1 evidence wins ties. If neither text class matched,
                // use the class-0 bubble container as a conservative fallback.
                boolean forcedFreeText = Boolean.TRUE.equals(FORCED_FREE_TEXT.get(group));
                boolean isBubbleText = !forcedFreeText && (bubbleTextScore > 0d
                        ? bubbleTextScore >= freeTextScore
                        : freeTextScore == 0d && findBubbleContainer(bounds, regions) >= 0);

                boolean isFreeText = forcedFreeText || (!isBubbleText && freeTextScore > 0d);
                OVERLAY_IS_BUBBLE.put(group, Boolean.valueOf(isBubbleText));
                OVERLAY_IS_FREE_TEXT.put(group, Boolean.valueOf(isFreeText));
                if (!isBubbleText) {
                    DetectedRegion pageMask = null;
                    for (DetectedRegion candidate : regions) {
                        if (candidate != null && candidate.getKind() == OcrRegionKind.PAGE_BUBBLE_MASK
                                && candidate.maskDataView() != null) {
                            pageMask = candidate;
                            break;
                        }
                    }
                    if (pageMask != null) OVERLAY_FREE_EXCLUSION_MASKS.put(group, pageMask);
                    else OVERLAY_FREE_EXCLUSION_MASKS.remove(group);
                } else {
                    OVERLAY_FREE_EXCLUSION_MASKS.remove(group);
                }
                // Forced free-text panels use the host's rectangular fill and
                // FreeTextLayout. A speech-balloon mask clipped this rectangle
                // at the balloon edge, leaving the old lettering visible there.
                boolean keepBubbleFillMask = isBubbleText;
                if (keepBubbleFillMask) {
                    String maskKey = findOverlayBubbleMaskId(bounds, regions);
                    DetectedRegion localMask = createOverlayBubbleMask(bounds, regions);
                    OVERLAY_MASK_SOURCES.put(group, regions);
                    OVERLAY_MASK_KEYS.put(group, maskKey == null ? "" : maskKey);
                    if (localMask != null) {
                        OVERLAY_BUBBLE_MASKS.put(group, localMask);
                        bubbleMasksReady++;
                    } else {
                        OVERLAY_BUBBLE_MASKS.remove(group);
                        bubbleMaskFallbacks++;
                    }
                    if (isBubbleText) bubbleOverlays.add(group);
                    else underlays.add(group);
                } else {
                    OVERLAY_BUBBLE_MASKS.remove(group);
                    OVERLAY_MASK_SOURCES.remove(group);
                    OVERLAY_MASK_KEYS.remove(group);
                    underlays.add(group);
                }
            }

            if (bubbleOverlays.isEmpty()) return;
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) overlayGroups;
            mutable.clear();
            mutable.addAll(underlays);
            mutable.addAll(bubbleOverlays);
            Log.i(TAG, "overlay draw priority: free_or_unknown_first=" + underlays.size()
                    + " bubble_last=" + bubbleOverlays.size()
                    + " bubble_masks_ready=" + bubbleMasksReady
                    + " bubble_mask_fallbacks=" + bubbleMaskFallbacks
                    + " unreadable_bounds=" + unreadable);
        } catch (Throwable error) {
            Log.w(TAG, "Overlay draw priority failed; original order retained", error);
        }
    }


    /**
     * Applies the checkbox as an authoritative rendering mode, not as an OCR
     * classification hint. Once forced, this panel must not be passed through
     * BubblePop association, bubble-mask clipping or bubble text layout again.
     */
    private static boolean enforceForcedFreeText(Object overlay) {
        if (overlay == null || !Boolean.TRUE.equals(FORCED_FREE_TEXT.get(overlay))) {
            return false;
        }
        OVERLAY_IS_BUBBLE.put(overlay, Boolean.FALSE);
        OVERLAY_IS_FREE_TEXT.put(overlay, Boolean.TRUE);
        OVERLAY_BUBBLE_MASKS.remove(overlay);
        OVERLAY_MASK_SOURCES.remove(overlay);
        OVERLAY_MASK_KEYS.remove(overlay);
        OVERLAY_FREE_EXCLUSION_MASKS.remove(overlay);
        return true;
    }

    /** Re-associate an automatic panel with the current OCR frame on every render. */
    public static void refreshOverlayAssociation(Object overlay) {
        if (overlay == null) return;
        if (enforceForcedFreeText(overlay)) return;
        Rect bounds = readOverlayBounds(overlay);
        if (bounds == null) return;
        List<DetectedRegion> best = null;
        double bestScore = 0d;
        synchronized (LOCK) {
            for (List<DetectedRegion> candidate : ACTIVE_REGIONS.values()) {
                if (candidate == null || candidate.isEmpty()) continue;
                double bubbleScore = bestTextRegionScore(bounds, candidate, OcrRegionKind.SPEECH_BUBBLE);
                double freeScore = bestTextRegionScore(bounds, candidate, OcrRegionKind.FREE_TEXT);
                double score = Math.max(bubbleScore, freeScore);
                if (freeScore == 0d && findBubbleContainer(bounds, candidate) >= 0) score = Math.max(score, 0.18d);
                if (score > bestScore) { bestScore = score; best = candidate; }
            }
        }
        boolean forcedFreeText = Boolean.TRUE.equals(FORCED_FREE_TEXT.get(overlay));
        if (best == null) {
            OVERLAY_BUBBLE_MASKS.remove(overlay);
            OVERLAY_MASK_SOURCES.remove(overlay);
            OVERLAY_MASK_KEYS.remove(overlay);
            OVERLAY_IS_BUBBLE.remove(overlay);
            OVERLAY_IS_FREE_TEXT.put(overlay, Boolean.valueOf(forcedFreeText));
            OVERLAY_FREE_EXCLUSION_MASKS.remove(overlay);
            return;
        }
        double bubbleScore = bestTextRegionScore(bounds, best, OcrRegionKind.SPEECH_BUBBLE);
        double freeScore = bestTextRegionScore(bounds, best, OcrRegionKind.FREE_TEXT);
        boolean detectedBubble = bubbleScore > 0d ? bubbleScore >= freeScore
                : freeScore == 0d && findBubbleContainer(bounds, best) >= 0;
        boolean isBubble = !forcedFreeText && detectedBubble;
        OVERLAY_IS_BUBBLE.put(overlay, Boolean.valueOf(isBubble));
        OVERLAY_IS_FREE_TEXT.put(overlay,
                Boolean.valueOf(forcedFreeText || (!isBubble && freeScore > 0d)));

        // A forced free-text panel still needs the bubble's clipped fill mask.
        // Otherwise the exclusion mask deliberately leaves the original bubble
        // lettering untouched, which makes the panel opacity appear broken.
        // A manually forced free-text overlay uses a rectangular fill and
        // normal free-text layout, not the clipped speech-balloon renderer.
        boolean keepBubbleFillMask = !forcedFreeText && detectedBubble;
        if (keepBubbleFillMask) {
            OVERLAY_FREE_EXCLUSION_MASKS.remove(overlay);
            String maskKey = findOverlayBubbleMaskId(bounds, best);
            String previousKey = OVERLAY_MASK_KEYS.get(overlay);
            DetectedRegion cachedMask = OVERLAY_BUBBLE_MASKS.get(overlay);
            if (OVERLAY_MASK_SOURCES.get(overlay) == best
                    && maskKey != null && maskKey.equals(previousKey)
                    && cachedMask != null && cachedMask.maskDataView() != null) {
                return;
            }
            DetectedRegion local = createOverlayBubbleMask(bounds, best);
            OVERLAY_MASK_SOURCES.put(overlay, best);
            OVERLAY_MASK_KEYS.put(overlay, maskKey == null ? "" : maskKey);
            if (local != null) OVERLAY_BUBBLE_MASKS.put(overlay, local);
            else OVERLAY_BUBBLE_MASKS.remove(overlay);
        } else {
            OVERLAY_BUBBLE_MASKS.remove(overlay);
            OVERLAY_MASK_SOURCES.remove(overlay);
            OVERLAY_MASK_KEYS.remove(overlay);
            DetectedRegion pageMask = null;
            for (DetectedRegion candidate : best) {
                if (candidate != null && candidate.getKind() == OcrRegionKind.PAGE_BUBBLE_MASK
                        && candidate.maskDataView() != null) { pageMask = candidate; break; }
            }
            if (pageMask != null) OVERLAY_FREE_EXCLUSION_MASKS.put(overlay, pageMask);
            else OVERLAY_FREE_EXCLUSION_MASKS.remove(overlay);
        }
    }

    /** Returns whether the editor explicitly fixed this panel as free text. */
    public static boolean isForcedFreeTextOverlay(Object overlay) {
        return overlay != null && Boolean.TRUE.equals(FORCED_FREE_TEXT.get(overlay));
    }

    /** Enables the manual free-text override; false restores detector auto-classification. */
    public static void setForceFreeTextOverlay(Object overlay, boolean enabled) {
        if (overlay == null) return;
        if (enabled) {
            FORCED_FREE_TEXT.put(overlay, Boolean.TRUE);
            enforceForcedFreeText(overlay);
            Log.i(TAG, "Panel forced to independent free-text rendering; BubblePop association disabled");
        } else {
            FORCED_FREE_TEXT.remove(overlay);
            refreshOverlayAssociation(overlay);
            Log.i(TAG, "Panel returned to automatic OCR/BubblePop classification");
        }
    }

    /**
     * Applies a separate background fill alpha only to a detector-classified free-text overlay.
     * Unknown and bubble overlays preserve the exact host alpha.
     */
    public static int resolveBackgroundAlpha(Context context, Object overlay, int originalAlpha) {
        if (overlay == null) return originalAlpha;
        // Preserve APK219's original contract: a non-bubble overlay may use
        // the separate free-text alpha even when RT-DETR has no direct FREE_TEXT
        // score for its bounds. Requiring a positive score here made all such
        // backgrounds revert to the host's fully opaque alpha.
        try {
            boolean forcedFreeText = enforceForcedFreeText(overlay);
            Integer override = FREE_TEXT_ALPHA_OVERRIDES.get(overlay);
            int percent;
            if (override != null) {
                percent = override.intValue();
            } else {
                Boolean isBubble = OVERLAY_IS_BUBBLE.get(overlay);
                // Match the editor's contract: unknown classification is treated
                // as non-bubble for free-text opacity. Explicit bubble evidence
                // always preserves the host's original fill alpha.
                if (!forcedFreeText && Boolean.TRUE.equals(isBubble)) return originalAlpha;
                if (context == null) return originalAlpha;
                android.content.SharedPreferences prefs =
                        context.getSharedPreferences(OCR_PREFS, Context.MODE_PRIVATE);
                if (!prefs.getBoolean(FREE_ALPHA_ENABLED, false)) return originalAlpha;
                percent = prefs.getInt(FREE_ALPHA_PERCENT, -1);
                if (percent < 0) return originalAlpha;
            }
            percent = Math.max(0, Math.min(100, percent));
            return Math.max(0, Math.min(255, Math.round(percent * 255f / 100f)));
        } catch (Throwable error) {
            Log.w(TAG, "Separate free-text alpha lookup failed; original alpha retained", error);
            return originalAlpha;
        }
    }

    /** The editor uses the same non-bubble classification as APK219's alpha path. */
    public static boolean isFreeTextOverlay(Object overlay) {
        return overlay != null && (Boolean.TRUE.equals(FORCED_FREE_TEXT.get(overlay))
                || !Boolean.TRUE.equals(OVERLAY_IS_BUBBLE.get(overlay)));
    }

    /** Sets a per-overlay fill opacity override without changing other free-text rectangles. */
    public static void setFreeTextBackgroundAlphaPercent(Object overlay, int percent) {
        if (overlay == null) return;
        FREE_TEXT_ALPHA_OVERRIDES.put(overlay, Integer.valueOf(Math.max(0, Math.min(100, percent))));
    }

    public static int getFreeTextBackgroundAlphaOverridePercent(Object overlay) {
        Integer value = overlay == null ? null : FREE_TEXT_ALPHA_OVERRIDES.get(overlay);
        return value == null ? -1 : value.intValue();
    }

    public static int getFreeTextBackgroundAlphaPercent(
            Context context, Object overlay, int originalAlpha) {
        int alpha = resolveBackgroundAlpha(context, overlay, originalAlpha);
        return Math.max(0, Math.min(100, Math.round(alpha * 100f / 255f)));
    }

    public static void clearFreeTextBackgroundAlpha(Object overlay) {
        if (overlay != null) FREE_TEXT_ALPHA_OVERRIDES.remove(overlay);
    }

    private static boolean isSeparateFreeTextAlphaEnabled(Context context) {
        if (context == null) return false;
        try {
            return context.getSharedPreferences(OCR_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(FREE_ALPHA_ENABLED, false);
        } catch (Throwable error) {
            Log.w(TAG, "Could not read free-text alpha preference", error);
            return false;
        }
    }

    private static boolean isPaddleSelected() {
        try {
            Class<?> selector = Class.forName("vh.l");
            Object selected = selector.getMethod("c").invoke(null);
            return PADDLE_ENGINE_NAME.equals(selected);
        } catch (Throwable error) {
            Log.w(TAG, "Could not read OCR selector; preserving current PP-OCRv6 route", error);
            return true;
        }
    }

    private static Rect readOverlayBounds(Object overlay) {
        if (overlay == null) return null;
        try {
            // Host overlay model Loh/m exposes bounds via b()=left, d()=top,
            // c()=right, a()=bottom. These are the same accessors used by vh/j.l.
            Class<?> type = overlay.getClass();
            int left = ((Number) type.getMethod("b").invoke(overlay)).intValue();
            int top = ((Number) type.getMethod("d").invoke(overlay)).intValue();
            int right = ((Number) type.getMethod("c").invoke(overlay)).intValue();
            int bottom = ((Number) type.getMethod("a").invoke(overlay)).intValue();
            if (right <= left || bottom <= top) return null;
            return new Rect(left, top, right, bottom);
        } catch (Throwable error) {
            return null;
        }
    }

    private static double bestTextRegionScore(
            Rect text, List<DetectedRegion> regions, OcrRegionKind kind) {
        long textArea = (long) text.width() * text.height();
        if (textArea <= 0L) return 0d;
        double best = 0d;
        int centerX = text.centerX();
        int centerY = text.centerY();
        for (DetectedRegion region : regions) {
            if (region.getKind() != kind) continue;
            Rect detected = region.getBounds();
            Rect intersection = new Rect(text);
            if (!intersection.intersect(detected)) continue;
            long intersectionArea = (long) intersection.width() * intersection.height();
            long detectedArea = (long) detected.width() * detected.height();
            long smallerArea = Math.min(textArea, detectedArea);
            if (smallerArea <= 0L) continue;
            double score = (double) intersectionArea / (double) smallerArea;
            if (detected.contains(centerX, centerY)) score = Math.max(score, 0.5d);
            if (score >= 0.18d && score > best) best = score;
        }
        return best;
    }

    private static String findBubbleContainerId(Rect text, List<DetectedRegion> regions) {
        ArrayList<BubbleRegionMatcher.Bounds> bubbles = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        for (DetectedRegion region : regions) {
            if (region.getKind() != OcrRegionKind.BUBBLE_CONTAINER) continue;
            Rect bounds = region.getBounds();
            bubbles.add(new BubbleRegionMatcher.Bounds(
                    bounds.left, bounds.top, bounds.right, bounds.bottom));
            ids.add(region.getId());
        }
        int index = BubbleRegionMatcher.findBestContainer(
                text.left, text.top, text.right, text.bottom, bubbles);
        return index >= 0 && index < ids.size() ? ids.get(index) : null;
    }

    private static String findOverlayBubbleMaskId(
            Rect overlayBounds, List<DetectedRegion> regions) {
        if (overlayBounds == null || regions == null) return null;
        DetectedRegion pageMask = null;
        for (DetectedRegion region : regions) {
            if (region != null && region.getKind() == OcrRegionKind.PAGE_BUBBLE_MASK
                    && region.maskDataView() != null) {
                pageMask = region;
                break;
            }
        }
        if (pageMask == null) return null;
        int containerIndex = findBubbleContainer(overlayBounds, regions);
        if (containerIndex < 0) return null;
        int current = 0;
        for (DetectedRegion region : regions) {
            if (region.getKind() != OcrRegionKind.BUBBLE_CONTAINER) continue;
            if (current++ == containerIndex) {
                return pageMask.getId() + ":container:" + region.getId();
            }
        }
        return null;
    }

    private static DetectedRegion createOverlayBubbleMask(
            Rect overlayBounds, List<DetectedRegion> regions) {
        DetectedRegion pageMask = null;
        for (DetectedRegion region : regions) {
            if (region.getKind() == OcrRegionKind.PAGE_BUBBLE_MASK
                    && region.maskDataView() != null) {
                pageMask = region;
                break;
            }
        }
        if (pageMask == null) {
            Log.d(TAG, "BubblePop mask unavailable: page mask missing");
            return null;
        }
        int containerIndex = findBubbleContainer(overlayBounds, regions);
        if (containerIndex < 0) {
            Log.d(TAG, "BubblePop mask unavailable: overlay did not match a BUBBLE_CONTAINER; bounds="
                    + overlayBounds.flattenToString());
            return null;
        }
        DetectedRegion container = null;
        int current = 0;
        for (DetectedRegion region : regions) {
            if (region.getKind() != OcrRegionKind.BUBBLE_CONTAINER) continue;
            if (current++ == containerIndex) { container = region; break; }
        }
        if (container == null) {
            Log.d(TAG, "BubblePop mask unavailable: matched container index was not resolved");
            return null;
        }

        Rect pageBounds = pageMask.getBounds();
        Rect crop = container.getBounds();
        if (!crop.intersect(pageBounds)) {
            Log.d(TAG, "BubblePop mask unavailable: container outside page-mask bounds");
            return null;
        }
        int width = crop.width();
        int height = crop.height();
        if (width <= 0 || height <= 0 || (long) width * height > 16000000L) {
            Log.d(TAG, "BubblePop mask unavailable: invalid crop=" + width + "x" + height);
            return null;
        }
        byte[] source = pageMask.maskDataView();
        int sourceWidth = pageMask.getMaskWidth();
        int sourceHeight = pageMask.getMaskHeight();
        if (source == null || sourceWidth != pageBounds.width()
                || sourceHeight != pageBounds.height()
                || source.length != sourceWidth * sourceHeight) {
            Log.w(TAG, "BubblePop mask unavailable: source mask dimensions do not match bounds");
            return null;
        }

        byte[] local = new byte[width * height];
        int foreground = 0;
        for (int y = 0; y < height; y++) {
            int sourceOffset = (crop.top - pageBounds.top + y) * sourceWidth
                    + (crop.left - pageBounds.left);
            int targetOffset = y * width;
            for (int x = 0; x < width; x++) {
                byte pixel = source[sourceOffset + x];
                local[targetOffset + x] = pixel;
                if ((pixel & 0xff) != 0) foreground++;
            }
        }
        if (foreground < 40) {
            Log.d(TAG, "BubblePop mask too sparse for overlay; foreground=" + foreground
                    + " crop=" + width + "x" + height + "; rectangular fallback");
            return null;
        }
        return new DetectedRegion(
                pageMask.getId() + ":container:" + container.getId(),
                OcrRegionKind.PAGE_BUBBLE_MASK, crop, pageMask.getConfidence(),
                null, null, local, width, height);
    }

    /** Called by both native overlay renderers before rectangular StaticLayout fallback. */
    public static boolean drawBubbleText(
            android.graphics.Canvas canvas, Object overlay, CharSequence text,
            android.text.TextPaint paint, float minTextSize, float maxTextSize, int padding) {
        if (canvas == null || overlay == null || text == null || paint == null) return false;
        try {
            if (enforceForcedFreeText(overlay)) return false;
            refreshOverlayAssociation(overlay);
            DetectedRegion region = OVERLAY_BUBBLE_MASKS.get(overlay);
            if (region == null || region.maskDataView() == null) return false;
            Rect overlayBounds = readOverlayBounds(overlay);
            if (overlayBounds == null) return false;
            float[] transform = BUBBLE_TEXT_TRANSFORMS.get(overlay);
            if (transform == null) transform = new float[] {0f, 0f, 1f, 1f};
            Context context = resolveContextFromOverlay(overlay);
            boolean autoWrap = context == null || context.getSharedPreferences(
                    OCR_PREFS, Context.MODE_PRIVATE).getBoolean(BUBBLE_AUTO_WRAP, true);
            // Overflow beyond the detected speech balloon is a BubblePop-only
            // behavior. The ordinary renderer keeps the historical mask boundary.
            boolean allowOverflow = context != null && isBubblePopPageMaskEnabled(context);
            return BubbleTextLayout.draw(canvas, region.maskDataView(),
                    region.getMaskWidth(), region.getMaskHeight(), region.getBounds(),
                    overlayBounds, text, paint, minTextSize, maxTextSize, padding,
                    autoWrap, transform[0], transform[1], transform[2],
                    transform.length > 3 ? transform[3] : 1.0f, allowOverflow);
        } catch (Throwable error) {
            Log.w(TAG, "BubblePop text layout failed; rectangular host layout retained", error);
            return false;
        }
    }

    /** Draws free-text overlays with the same per-block typography controls. */
    public static boolean drawFreeText(android.graphics.Canvas canvas, Object overlay,
            CharSequence text, android.text.TextPaint paint,
            float minTextSize, float maxTextSize, int padding) {
        if (canvas == null || overlay == null || text == null || paint == null || text.length() == 0) return false;
        try {
            // The checkbox wins over detector output on every draw, including
            // after the panel has been moved over or across a speech balloon.
            // Do not let a stale bubble classification route it back to BubbleTextLayout.
            boolean forcedFreeText = enforceForcedFreeText(overlay);
            if (!forcedFreeText) refreshOverlayAssociation(overlay);
            // Free-text blocks are independent rectangles. Do not associate them
            // with BubblePop masks or speech-balloon contours during drawing.
            Boolean bubble = OVERLAY_IS_BUBBLE.get(overlay);
            if (Boolean.TRUE.equals(bubble)) return false;
            Rect bounds = readOverlayBounds(overlay);
            if (bounds == null) return false;
            float[] transform = BUBBLE_TEXT_TRANSFORMS.get(overlay);
            if (transform == null) transform = new float[] {0f, 0f, 1f, 1f, 0f};
            return FreeTextLayout.draw(canvas, bounds, text, paint, minTextSize, maxTextSize,
                    padding, transform[0], transform[1], transform[2],
                    transform.length > 3 ? transform[3] : 1.0f,
                    transform.length > 4 ? transform[4] : 0f);
        } catch (Throwable error) {
            Log.w(TAG, "Free-text layout failed; rectangular host layout retained", error);
            return false;
        }
    }

    public static boolean drawBubbleBackground(android.graphics.Canvas canvas, Object overlay,
            float radius, android.graphics.Paint paint) {
        try {
            // The host paints the fill before drawing text. Refresh the shape here,
            // not later in drawBubbleText(), so the fill gets the same contour.
            // Forced free text is a strict rectangular-background mode. Return
            // to the host's normal rounded rectangle before any BubblePop clipping.
            if (enforceForcedFreeText(overlay)) return false;
            refreshOverlayAssociation(overlay);
            Rect bounds = readOverlayBounds(overlay);
            if (bounds == null) return false;
            Boolean isBubble = OVERLAY_IS_BUBBLE.get(overlay);
            DetectedRegion region = OVERLAY_BUBBLE_MASKS.get(overlay);
            if (isBubble != null && !isBubble.booleanValue()
                    && !Boolean.TRUE.equals(FORCED_FREE_TEXT.get(overlay))) {
                DetectedRegion exclusion = OVERLAY_FREE_EXCLUSION_MASKS.get(overlay);
                return exclusion != null && BubbleTextLayout.drawExclusionClippedBackground(
                        canvas, exclusion, bounds.left, bounds.top, bounds.right, bounds.bottom,
                        radius, radius, paint);
            }
            return region != null && BubbleTextLayout.drawClippedBackground(
                    canvas, region, bounds.left, bounds.top, bounds.right, bounds.bottom,
                    radius, radius, paint);
        } catch (Throwable error) {
            Log.w(TAG, "BubblePop background clipping failed; rectangular fallback retained", error);
            return false;
        }
    }

    public static boolean hasBubbleMask(Object overlay) {
        return overlay != null && OVERLAY_BUBBLE_MASKS.containsKey(overlay);
    }

    /**
     * Clips the independently rendered transparent translation layer to the
     * same BubblePop masks used by the live renderer. originX/originY describe
     * the screen coordinate represented by pixel (0,0) in overlayBitmap.
     * The source comic bitmap is intentionally never modified by this method.
     */
    public static int clipBubbleOverlay(Bitmap overlayBitmap, int originX, int originY) {
        if (overlayBitmap == null || overlayBitmap.isRecycled()) return 0;

        final List<DetectedRegion> masks;
        synchronized (OVERLAY_BUBBLE_MASKS) {
            masks = new ArrayList<>(OVERLAY_BUBBLE_MASKS.values());
        }
        if (masks.isEmpty()) return 0;

        Set<String> seen = new HashSet<>();
        int applied = 0;
        for (DetectedRegion region : masks) {
            if (region == null) continue;
            String id = region.getId();
            if (id != null && !seen.add(id)) continue;

            Rect bounds = region.getBounds();
            byte[] mask = region.maskDataView();
            int maskWidth = region.getMaskWidth();
            int maskHeight = region.getMaskHeight();
            if (bounds == null || mask == null || maskWidth <= 0 || maskHeight <= 0
                    || mask.length != maskWidth * maskHeight
                    || bounds.width() != maskWidth || bounds.height() != maskHeight) {
                continue;
            }

            int left = Math.max(0, bounds.left - originX);
            int top = Math.max(0, bounds.top - originY);
            int right = Math.min(overlayBitmap.getWidth(), bounds.right - originX);
            int bottom = Math.min(overlayBitmap.getHeight(), bounds.bottom - originY);
            if (right <= left || bottom <= top) continue;

            int width = right - left;
            int height = bottom - top;
            int[] pixels = new int[width * height];
            overlayBitmap.getPixels(pixels, 0, width, left, top, width, height);
            // Shrink only the translated fill's clipping edge, preserving the source comic contour.
            byte[] innerMask = BubbleOverlayMasker.erodeMask(mask, maskWidth, maskHeight, 1);
            int changed = BubbleOverlayMasker.applyMask(
                    pixels, width, height, left + originX, top + originY,
                    bounds.left, bounds.top, innerMask, maskWidth, maskHeight);
            if (changed > 0) {
                overlayBitmap.setPixels(pixels, 0, width, left, top, width, height);
                applied++;
            }
        }

        Log.i(TAG, "screenshot BubblePop clipping applied to " + applied
                + " bubble overlay(s); masksAvailable=" + masks.size());
        return applied;
    }

    public static void setBubbleTextTransform(Object overlay, float dx, float dy, float scale) {
        if (overlay == null) return;
        float[] current = BUBBLE_TEXT_TRANSFORMS.get(overlay);
        float lineSpacing = current != null && current.length > 3 ? current[3] : 1.0f;
        setBubbleTextTransform(overlay, dx, dy, scale, lineSpacing);
    }

    public static void setBubbleTextTransform(
            Object overlay, float dx, float dy, float scale, float lineSpacing) {
        float rotation = 0f;
        float[] current = overlay == null ? null : BUBBLE_TEXT_TRANSFORMS.get(overlay);
        if (current != null && current.length > 4) rotation = current[4];
        setBubbleTextTransform(overlay, dx, dy, scale, lineSpacing, rotation);
    }

    public static void setBubbleTextTransform(
            Object overlay, float dx, float dy, float scale, float lineSpacing, float rotation) {
        if (overlay == null) return;
        BUBBLE_TEXT_TRANSFORMS.put(overlay, new float[] {
                dx, dy,
                Math.max(0.25f, Math.min(5.00f, scale)),
                Math.max(0.85f, Math.min(3.0f, lineSpacing)),
                Math.max(-180f, Math.min(180f, rotation))});
    }

    public static float[] getBubbleTextTransform(Object overlay) {
        float[] value = BUBBLE_TEXT_TRANSFORMS.get(overlay);
        return value == null ? new float[] {0f, 0f, 1f, 1f, 0f} : value.clone();
    }

    private static Context resolveContextFromOverlay(Object overlay) {
        try {
            synchronized (ACTIVE_REGIONS) {
                for (Object owner : ACTIVE_REGIONS.keySet()) {
                    Context context = resolveContext(owner);
                    if (context != null) return context;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int findBubbleContainer(Rect text, List<DetectedRegion> regions) {
        ArrayList<BubbleRegionMatcher.Bounds> bubbles = new ArrayList<>();
        for (DetectedRegion region : regions) {
            if (region.getKind() != OcrRegionKind.BUBBLE_CONTAINER) continue;
            Rect bounds = region.getBounds();
            bubbles.add(new BubbleRegionMatcher.Bounds(
                    bounds.left, bounds.top, bounds.right, bounds.bottom));
        }
        return BubbleRegionMatcher.findBestContainer(
                text.left, text.top, text.right, text.bottom, bubbles);
    }

    private static void removeIfCurrent(Object owner, PendingFrame expected) {
        synchronized (LOCK) {
            if (PENDING.get(owner) == expected) PENDING.remove(owner);
        }
    }

    private static final class PendingFrame {
        final Bitmap source;
        final FutureTask<List<DetectedRegion>> task;

        PendingFrame(Bitmap source, FutureTask<List<DetectedRegion>> task) {
            this.source = source;
            this.task = task;
        }
    }
}
