package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decoder for the dual-label YOLO segmentation head used by the prototype model.
 *
 * Tensor layout: [1, 38, anchors] in channel-first order:
 * cx, cy, width, height, bubble score, free-text score, 32 mask coefficients.
 * The first four values are normalized to model input dimensions.
 */
public final class YoloDualOutputDecoder {
    public static final int FEATURE_COUNT = 38;
    public static final int MASK_COEFFICIENT_COUNT = 32;
    public static final int CLASS_BUBBLE = 0;
    public static final int CLASS_FREE_TEXT = 1;
    public static final int MAX_DETECTIONS = 300;

    private YoloDualOutputDecoder() {}

    public static List<YoloDetection> decode(
            FloatBuffer tensor, int anchorCount, int inputWidth, int inputHeight,
            float confidenceThreshold, float nmsThreshold) {
        if (tensor == null || anchorCount <= 0 || inputWidth <= 0 || inputHeight <= 0
                || tensor.limit() != FEATURE_COUNT * anchorCount
                || !Float.isFinite(confidenceThreshold) || confidenceThreshold < 0f
                || confidenceThreshold > 1f || !Float.isFinite(nmsThreshold)
                || nmsThreshold < 0f || nmsThreshold > 1f) {
            throw new IllegalArgumentException("Invalid YOLO output tensor or decoder parameters");
        }

        FloatBuffer values = tensor.duplicate();
        List<YoloDetection> candidates = new ArrayList<>();
        for (int anchor = 0; anchor < anchorCount; anchor++) {
            float cx = feature(values, anchorCount, anchor, 0);
            float cy = feature(values, anchorCount, anchor, 1);
            float width = feature(values, anchorCount, anchor, 2);
            float height = feature(values, anchorCount, anchor, 3);
            float bubbleScore = feature(values, anchorCount, anchor, 4);
            float textScore = feature(values, anchorCount, anchor, 5);

            if (!Float.isFinite(cx) || !Float.isFinite(cy) || !Float.isFinite(width)
                    || !Float.isFinite(height) || !Float.isFinite(bubbleScore)
                    || !Float.isFinite(textScore) || width <= 0f || height <= 0f) {
                continue;
            }
            int classId = bubbleScore >= textScore ? CLASS_BUBBLE : CLASS_FREE_TEXT;
            float confidence = Math.max(bubbleScore, textScore);
            if (confidence < confidenceThreshold || confidence < 0f || confidence > 1f) {
                continue;
            }

            float centerX = cx * inputWidth;
            float centerY = cy * inputHeight;
            float boxWidth = width * inputWidth;
            float boxHeight = height * inputHeight;
            float left = clamp(centerX - boxWidth / 2f, 0f, inputWidth);
            float top = clamp(centerY - boxHeight / 2f, 0f, inputHeight);
            float right = clamp(centerX + boxWidth / 2f, 0f, inputWidth);
            float bottom = clamp(centerY + boxHeight / 2f, 0f, inputHeight);
            if (right - left <= 1f || bottom - top <= 1f) continue;

            float[] coefficients = new float[MASK_COEFFICIENT_COUNT];
            boolean valid = true;
            for (int i = 0; i < MASK_COEFFICIENT_COUNT; i++) {
                float coefficient = feature(values, anchorCount, anchor, 6 + i);
                if (!Float.isFinite(coefficient)) {
                    valid = false;
                    break;
                }
                coefficients[i] = coefficient;
            }
            if (!valid) continue;
            candidates.add(new YoloDetection(
                    classId == CLASS_BUBBLE ? OcrRegionKind.SPEECH_BUBBLE : OcrRegionKind.FREE_TEXT,
                    left, top, right, bottom, confidence, coefficients));
        }

        candidates.sort(new Comparator<YoloDetection>() {
            @Override public int compare(YoloDetection a, YoloDetection b) {
                return Float.compare(b.getConfidence(), a.getConfidence());
            }
        });

        List<YoloDetection> kept = new ArrayList<>();
        for (YoloDetection candidate : candidates) {
            boolean suppressed = false;
            for (YoloDetection previous : kept) {
                if (candidate.getKind() == previous.getKind()
                        && intersectionOverUnion(candidate, previous) > nmsThreshold) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) kept.add(candidate);
            if (kept.size() >= MAX_DETECTIONS) break;
        }
        return kept;
    }

    private static float feature(FloatBuffer values, int anchors, int anchor, int channel) {
        return values.get(channel * anchors + anchor);
    }

    private static float intersectionOverUnion(YoloDetection a, YoloDetection b) {
        float width = Math.max(0f, Math.min(a.getRight(), b.getRight())
                - Math.max(a.getLeft(), b.getLeft()));
        float height = Math.max(0f, Math.min(a.getBottom(), b.getBottom())
                - Math.max(a.getTop(), b.getTop()));
        float intersection = width * height;
        float areaA = (a.getRight() - a.getLeft()) * (a.getBottom() - a.getTop());
        float areaB = (b.getRight() - b.getLeft()) * (b.getBottom() - b.getTop());
        float union = areaA + areaB - intersection;
        return union <= 0f ? 0f : intersection / union;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
