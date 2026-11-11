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
                            int bubble = 0;
                            int free = 0;
                            if (result != null) {
                                for (DetectedRegion region : result) {
                                    if (region.getKind() == OcrRegionKind.SPEECH_BUBBLE) bubble++;
                                    else if (region.getKind() == OcrRegionKind.FREE_TEXT) free++;
                                }
                            }
                            Log.i(TAG, "detected: total=" + (result == null ? 0 : result.size())
                                    + " text_bubble=" + bubble + " text_free=" + free
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
        if (owner == null || mlKitResult == null || source == null) return;

        final PendingFrame pending;
        synchronized (LOCK) {
            pending = PENDING.get(owner);
        }
        if (pending == null || pending.source != source) return;

        final List<DetectedRegion> regions;
        try {
            regions = pending.task.get(20, TimeUnit.SECONDS);
        } catch (Throwable error) {
            Log.w(TAG, "RT-DETR result unavailable; original OCR retained", error);
            removeIfCurrent(owner, pending);
            return;
        }
        removeIfCurrent(owner, pending);

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

            ArrayList<Object> matched = new ArrayList<>(originalCount);
            int unreadable = 0;
            for (Object block : blocks) {
                Rect textBounds = readBlockBounds(block);
                if (textBounds == null) {
                    unreadable++;
                    matched.add(block);
                    continue;
                }
                if (overlapsDetectedText(textBounds, regions)) {
                    matched.add(block);
                }
            }

            // Never blank a page because a detector threshold or model version was wrong.
            if (matched.isEmpty()) {
                Log.w(TAG, "No ML Kit blocks matched RT-DETR; original OCR retained");
                return;
            }

            int removed = originalCount - matched.size();
            if (removed > 0) {
                blocks.clear();
                blocks.addAll(matched);
            }
            Log.i(TAG, "hybrid OCR filter: detector_regions=" + regions.size()
                    + " mlkit_blocks=" + originalCount + " kept=" + matched.size()
                    + " filtered=" + removed + " unreadable_bounds=" + unreadable);
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
