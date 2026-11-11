package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Detector bridge: crops the visible capture area, letterboxes it for the model,
 * runs a replaceable inference backend and maps model boxes back to frame coordinates.
 */
public final class YoloDualRegionDetector implements OcrRegionDetector, AutoCloseable {
    private static final int PAD_COLOR = Color.rgb(114, 114, 114);
    private final YoloInference inference;
    private final float confidenceThreshold;
    private final float nmsThreshold;
    private boolean closed;

    public YoloDualRegionDetector(
            YoloInference inference, float confidenceThreshold, float nmsThreshold) {
        if (inference == null) throw new IllegalArgumentException("YOLO inference backend is required");
        if (!Float.isFinite(confidenceThreshold) || confidenceThreshold < 0f || confidenceThreshold > 1f
                || !Float.isFinite(nmsThreshold) || nmsThreshold < 0f || nmsThreshold > 1f) {
            throw new IllegalArgumentException("YOLO thresholds must be in [0, 1]");
        }
        if (inference.getInputWidth() <= 0 || inference.getInputHeight() <= 0) {
            throw new IllegalArgumentException("YOLO input dimensions must be positive");
        }
        this.inference = inference;
        this.confidenceThreshold = confidenceThreshold;
        this.nmsThreshold = nmsThreshold;
    }

    @Override
    public synchronized List<DetectedRegion> detect(OcrFrame frame) throws Exception {
        if (closed) throw new IllegalStateException("YOLO detector is closed");
        if (frame == null) throw new IllegalArgumentException("OCR frame is required");

        Bitmap source = frame.getBitmap();
        Rect visible = frame.getVisibleBounds();
        Bitmap crop = null;
        Bitmap modelInput = null;
        try {
            if (visible.left == 0 && visible.top == 0
                    && visible.right == source.getWidth() && visible.bottom == source.getHeight()) {
                crop = source;
            } else {
                crop = Bitmap.createBitmap(source, visible.left, visible.top,
                        visible.width(), visible.height());
            }

            int inputWidth = inference.getInputWidth();
            int inputHeight = inference.getInputHeight();
            float fit = Math.min((float) inputWidth / crop.getWidth(),
                    (float) inputHeight / crop.getHeight());
            int resizedWidth = Math.max(1, Math.min(inputWidth, Math.round(crop.getWidth() * fit)));
            int resizedHeight = Math.max(1, Math.min(inputHeight, Math.round(crop.getHeight() * fit)));
            float padLeft = (inputWidth - resizedWidth) / 2f;
            float padTop = (inputHeight - resizedHeight) / 2f;
            YoloLetterboxTransform transform = new YoloLetterboxTransform(
                    crop.getWidth(), crop.getHeight(), inputWidth, inputHeight,
                    resizedWidth, resizedHeight, padLeft, padTop);

            modelInput = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(modelInput);
            canvas.drawColor(PAD_COLOR);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(crop, null,
                    new RectF(padLeft, padTop, padLeft + resizedWidth, padTop + resizedHeight),
                    paint);

            YoloInferenceOutput output = inference.run(modelInput);
            if (output == null) throw new IllegalStateException("YOLO backend returned null output");
            FloatBuffer tensor = output.getDetections();
            List<YoloDetection> decoded = YoloDualOutputDecoder.decode(
                    tensor, output.getAnchorCount(), inputWidth, inputHeight,
                    confidenceThreshold, nmsThreshold);

            List<DetectedRegion> regions = new ArrayList<>(decoded.size());
            int index = 0;
            for (YoloDetection detection : decoded) {
                int left = visible.left + (int) Math.floor(transform.toSourceX(detection.getLeft()));
                int top = visible.top + (int) Math.floor(transform.toSourceY(detection.getTop()));
                int right = visible.left + (int) Math.ceil(transform.toSourceX(detection.getRight()));
                int bottom = visible.top + (int) Math.ceil(transform.toSourceY(detection.getBottom()));
                Rect localBounds = frame.clampToVisibleBounds(new Rect(left, top, right, bottom));
                if (localBounds == null) continue;
                regions.add(new DetectedRegion(
                        "yolo:" + index++, detection.getKind(), localBounds,
                        detection.getConfidence(), null));
            }
            return regions;
        } finally {
            if (modelInput != null && !modelInput.isRecycled()) modelInput.recycle();
            if (crop != null && crop != source && !crop.isRecycled()) crop.recycle();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        inference.close();
    }
}
