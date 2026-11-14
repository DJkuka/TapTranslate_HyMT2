package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class RtDetrComicRegionDetector implements OcrRegionDetector, AutoCloseable {
    private static final String PERF_TAG = "TapTranslatePerf";
    private final RtDetrInference inference;
    private boolean closed;

    public RtDetrComicRegionDetector(RtDetrInference inference) {
        if (inference == null) throw new IllegalArgumentException("inference");
        this.inference = inference;
    }

    @Override
    public List<DetectedRegion> detect(OcrFrame frame) throws Exception {
        if (closed) throw new IllegalStateException("closed");
        final long totalStartNs = SystemClock.elapsedRealtimeNanos();
        Bitmap src = frame.getBitmap();
        String frameId = Integer.toHexString(System.identityHashCode(src));
        Rect visible = frame.getVisibleBounds();

        long cropStartNs = SystemClock.elapsedRealtimeNanos();
        Bitmap crop = (visible.left == 0 && visible.top == 0
                && visible.right == src.getWidth() && visible.bottom == src.getHeight())
                ? src : Bitmap.createBitmap(src, visible.left, visible.top, visible.width(), visible.height());
        long cropMs = (SystemClock.elapsedRealtimeNanos() - cropStartNs) / 1_000_000L;

        try {
            long inferenceStartNs = SystemClock.elapsedRealtimeNanos();
            RtDetrInferenceOutput raw = inference.run(crop);
            long inferenceMs = (SystemClock.elapsedRealtimeNanos() - inferenceStartNs) / 1_000_000L;

            long decodeStartNs = SystemClock.elapsedRealtimeNanos();
            List<RtDetrOutputDecoder.Detection> detections =
                    RtDetrOutputDecoder.decode(raw, crop.getWidth(), crop.getHeight(), .30f);
            long decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStartNs) / 1_000_000L;

            long mapStartNs = SystemClock.elapsedRealtimeNanos();
            List<DetectedRegion> out = new ArrayList<>();
            int n = 0;
            for (RtDetrOutputDecoder.Detection detection : detections) {
                Rect bounds = detection.bounds;
                bounds.offset(visible.left, visible.top);
                Rect clipped = frame.clampToVisibleBounds(bounds);
                if (clipped != null) {
                    out.add(new DetectedRegion("rtdetr:" + (n++), detection.kind, clipped, detection.confidence, null));
                }
            }
            long mapMs = (SystemClock.elapsedRealtimeNanos() - mapStartNs) / 1_000_000L;
            long totalMs = (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L;
            Log.i(PERF_TAG, "event=rtdetr_region_pipeline frame=" + frameId
                    + " crop_ms=" + cropMs
                    + " onnx_call_ms=" + inferenceMs
                    + " decode_ms=" + decodeMs
                    + " map_ms=" + mapMs
                    + " raw_detections=" + detections.size()
                    + " output_regions=" + out.size()
                    + " total_ms=" + totalMs);
            return out;
        } finally {
            if (crop != src && !crop.isRecycled()) crop.recycle();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            inference.close();
        }
    }
}
