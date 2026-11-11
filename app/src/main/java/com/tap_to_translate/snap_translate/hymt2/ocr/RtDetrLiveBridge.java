package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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
    private static final Map<Object, PendingFrame> PENDING =
            Collections.synchronizedMap(new WeakHashMap<Object, PendingFrame>());
    private static final Map<Object, List<DetectedRegion>> ACTIVE_REGIONS =
            Collections.synchronizedMap(new WeakHashMap<Object, List<DetectedRegion>>());
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

    private RtDetrLiveBridge() {}

    /** Called from the real vh.j.j(Bitmap) entry point before the existing OCR starts. */
    public static void onFrame(final Object owner, final Bitmap source) {
        if (owner == null || source == null || source.isRecycled()) return;

        final Context context = resolveContext(owner);
        if (context == null) {
            Log.w(TAG, "RT-DETR skipped: host OCR context unavailable");
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
                        try {
                            RtDetrComicRegionDetector active = getDetector(context);
                            OcrFrame frame = new OcrFrame(
                                    snapshot,
                                    new Rect(0, 0, snapshot.getWidth(), snapshot.getHeight()),
                                    0, 0, 1f, 1f);
                            List<DetectedRegion> result = active.detect(frame);
                            int containers = 0;
                            int bubbleText = 0;
                            int free = 0;
                            if (result != null) {
                                for (DetectedRegion region : result) {
                                    if (region.getKind() == OcrRegionKind.BUBBLE_CONTAINER) containers++;
                                    else if (region.getKind() == OcrRegionKind.SPEECH_BUBBLE) bubbleText++;
                                    else if (region.getKind() == OcrRegionKind.FREE_TEXT) free++;
                                }
                            }
                            Log.i(TAG, "detected: total=" + (result == null ? 0 : result.size())
                                    + " bubble_containers=" + containers
                                    + " text_bubble=" + bubbleText + " text_free=" + free
                                    + " frame=" + snapshot.getWidth() + "x" + snapshot.getHeight());
                            return result == null ? Collections.<DetectedRegion>emptyList() : result;
                        } catch (Throwable error) {
                            Log.e(TAG, "RT-DETR inference failed; original OCR will be used", error);
                            return Collections.emptyList();
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
            Object owner, Context ignoredContext, Object mlKitResult, Bitmap source) {
        if (owner == null || mlKitResult == null || source == null) {
            Log.w(TAG, "hybrid OCR skipped: null owner/result/source");
            return;
        }

        final PendingFrame pending;
        synchronized (LOCK) {
            pending = PENDING.get(owner);
        }
        if (pending == null) {
            Log.w(TAG, "hybrid OCR skipped: no pending RT-DETR frame for OCR owner");
            return;
        }
        if (pending.source != source) {
            Log.w(TAG, "hybrid OCR skipped: bitmap identity mismatch; frame="
                    + source.getWidth() + "x" + source.getHeight()
                    + " pending=" + pending.source.getWidth() + "x" + pending.source.getHeight());
            return;
        }

        final List<DetectedRegion> regions;
        try {
            regions = pending.task.get(20, TimeUnit.SECONDS);
        } catch (Throwable error) {
            Log.w(TAG, "RT-DETR result unavailable; original OCR retained", error);
            removeIfCurrent(owner, pending);
            return;
        }
        removeIfCurrent(owner, pending);
        synchronized (LOCK) {
            ACTIVE_REGIONS.put(owner, regions == null
                    ? Collections.<DetectedRegion>emptyList()
                    : new ArrayList<>(regions));
        }

        if (regions == null || regions.isEmpty()) {
            Log.w(TAG, "RT-DETR returned no text regions; original OCR retained");
            return;
        }

        try {
            Field blocksField = mlKitResult.getClass().getField("a");
            Object value = blocksField.get(mlKitResult);
            if (!(value instanceof List<?>)) {
                Log.w(TAG, "Unexpected ML Kit result container; original OCR retained");
                return;
            }

            @SuppressWarnings("unchecked")
            List<Object> blocks = (List<Object>) value;
            int originalCount = blocks.size();
            if (originalCount == 0) return;

            // RT-DETR boxes are not accurate enough to be a destructive OCR gate.
            // Keep every ML Kit block so a slightly clipped/missed detector box cannot
            // silently remove words or a whole line. Use the detector only for bubble
            // association/grouping; log coverage for later tuning.
            int detectorMatched = 0;
            int detectorUnmatched = 0;
            int unreadable = 0;
            for (Object block : blocks) {
                Rect textBounds = readBlockBounds(block);
                if (textBounds == null) {
                    unreadable++;
                } else if (overlapsDetectedText(textBounds, regions)) {
                    detectorMatched++;
                } else {
                    detectorUnmatched++;
                }
            }
            Log.i(TAG, "hybrid OCR classify: detector_regions=" + regions.size()
                    + " mlkit_blocks=" + originalCount + " retained=" + originalCount
                    + " detector_matched=" + detectorMatched
                    + " detector_unmatched=" + detectorUnmatched
                    + " filtering=disabled unreadable_bounds=" + unreadable);
        } catch (Throwable error) {
            Log.w(TAG, "Hybrid OCR filtering failed; original OCR retained", error);
        }
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
            if (!(blockListValue instanceof List<?>)) return true;
            List<?> blocks = (List<?>) blockListValue;
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
            if (firstContainer >= 0 && secondContainer >= 0
                    && firstContainer != secondContainer) {
                Log.i(TAG, "prevented cross-bubble OCR grouping: blocks="
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
        if (owner == null || overlayGroups == null || overlayGroups.size() < 2) return;
        final List<DetectedRegion> regions;
        synchronized (LOCK) {
            regions = ACTIVE_REGIONS.get(owner);
        }
        if (regions == null || regions.isEmpty()) return;

        try {
            ArrayList<Object> underlays = new ArrayList<>();
            ArrayList<Object> bubbleOverlays = new ArrayList<>();
            int unreadable = 0;
            for (Object group : overlayGroups) {
                Rect bounds = readOverlayBounds(group);
                if (bounds == null) {
                    unreadable++;
                    underlays.add(group);
                    continue;
                }

                double bubbleTextScore = bestTextRegionScore(
                        bounds, regions, OcrRegionKind.SPEECH_BUBBLE);
                double freeTextScore = bestTextRegionScore(
                        bounds, regions, OcrRegionKind.FREE_TEXT);

                // Direct class-1 evidence wins ties. If neither text class matched,
                // use the class-0 bubble container as a conservative fallback.
                boolean isBubbleText = bubbleTextScore > 0d
                        ? bubbleTextScore >= freeTextScore
                        : freeTextScore == 0d && findBubbleContainer(bounds, regions) >= 0;

                if (isBubbleText) bubbleOverlays.add(group);
                else underlays.add(group);
            }

            if (bubbleOverlays.isEmpty()) return;
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) overlayGroups;
            mutable.clear();
            mutable.addAll(underlays);
            mutable.addAll(bubbleOverlays);
            Log.i(TAG, "overlay draw priority: free_or_unknown_first=" + underlays.size()
                    + " bubble_last=" + bubbleOverlays.size()
                    + " unreadable_bounds=" + unreadable);
        } catch (Throwable error) {
            Log.w(TAG, "Overlay draw priority failed; original order retained", error);
        }
    }

    private static Rect readOverlayBounds(Object overlay) {
        if (overlay == null) return null;
        try {
            Class<?> type = overlay.getClass();
            Field left = field(type, "c");
            Field right = field(type, "d");
            Field top = field(type, "e");
            Field bottom = field(type, "f");
            int l = left.getInt(overlay);
            int r = right.getInt(overlay);
            int t = top.getInt(overlay);
            int b = bottom.getInt(overlay);
            if (r <= l || b <= t) return null;
            return new Rect(l, t, r, b);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException ignored) {
            Field result = type.getDeclaredField(name);
            result.setAccessible(true);
            return result;
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
