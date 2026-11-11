package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Engine-neutral coordinator:
 * 1) detects semantic regions,
 * 2) routes bubbles and free text to independent recognizer adapters,
 * 3) returns normalized text with both bitmap-local and screen coordinates.
 *
 * It deliberately does not know about ML Kit, Tesseract, PaddleOCR, YOLO, Hy-MT2,
 * or the obfuscated host model Loh/m.
 */
public final class HybridOcrPipeline {
    private final OcrRegionDetector detector;
    private final RegionTextRecognizer bubbleRecognizer;
    private final RegionTextRecognizer freeTextRecognizer;
    private final float minimumRegionConfidence;
    private final float minimumTextConfidence;

    public HybridOcrPipeline(
            OcrRegionDetector detector,
            RegionTextRecognizer bubbleRecognizer,
            RegionTextRecognizer freeTextRecognizer,
            float minimumRegionConfidence,
            float minimumTextConfidence) {
        if (detector == null || bubbleRecognizer == null || freeTextRecognizer == null) {
            throw new IllegalArgumentException("Detector and both OCR adapters are required");
        }
        checkThreshold(minimumRegionConfidence, "minimumRegionConfidence");
        checkThreshold(minimumTextConfidence, "minimumTextConfidence");
        this.detector = detector;
        this.bubbleRecognizer = bubbleRecognizer;
        this.freeTextRecognizer = freeTextRecognizer;
        this.minimumRegionConfidence = minimumRegionConfidence;
        this.minimumTextConfidence = minimumTextConfidence;
    }

    public OcrPipelineResult process(OcrFrame frame) throws Exception {
        if (frame == null) throw new IllegalArgumentException("Frame is required");
        List<DetectedRegion> raw = detector.detect(frame);
        if (raw == null) throw new IllegalStateException("Region detector returned null");

        List<DetectedRegion> accepted = new ArrayList<>();
        for (DetectedRegion region : raw) {
            if (region == null || region.getConfidence() < minimumRegionConfidence) continue;
            Rect clipped = frame.clampToVisibleBounds(region.getBounds());
            if (clipped == null) continue;
            accepted.add(new DetectedRegion(
                    region.getId(), region.getKind(), clipped,
                    region.getConfidence(), region.getContour()));
        }
        Collections.sort(accepted, new Comparator<DetectedRegion>() {
            @Override public int compare(DetectedRegion left, DetectedRegion right) {
                Rect a = left.getBounds();
                Rect b = right.getBounds();
                int byTop = Integer.compare(a.top, b.top);
                return byTop != 0 ? byTop : Integer.compare(a.left, b.left);
            }
        });

        List<OcrTextRegion> output = new ArrayList<>();
        for (DetectedRegion region : accepted) {
            RegionTextRecognizer recognizer = region.getKind() == OcrRegionKind.SPEECH_BUBBLE
                    ? bubbleRecognizer : freeTextRecognizer;
            List<RecognizedText> recognized = recognizer.recognize(frame, region);
            if (recognized == null) continue;

            int itemIndex = 0;
            for (RecognizedText item : recognized) {
                if (item == null || item.getConfidence() < minimumTextConfidence) continue;
                String cleanText = item.getText() == null ? "" : item.getText().trim();
                if (cleanText.isEmpty()) continue;

                Rect frameBounds = frame.clampToVisibleBounds(item.getBounds());
                if (frameBounds == null || intersectionOverUnion(frameBounds, region.getBounds()) < 0.10f) {
                    continue;
                }
                Rect screenBounds = frame.toScreenBounds(frameBounds);
                if (screenBounds == null) continue;

                String id = region.getId() + ":text:" + itemIndex++;
                output.add(new OcrTextRegion(
                        id,
                        region.getId(),
                        region.getKind(),
                        cleanText,
                        frameBounds,
                        screenBounds,
                        item.getConfidence(),
                        item.getEngineId()));
            }
        }

        deduplicateOverlappingResults(output);
        Collections.sort(output, new Comparator<OcrTextRegion>() {
            @Override public int compare(OcrTextRegion left, OcrTextRegion right) {
                Rect a = left.getScreenBounds();
                Rect b = right.getScreenBounds();
                int byTop = Integer.compare(a.top, b.top);
                return byTop != 0 ? byTop : Integer.compare(a.left, b.left);
            }
        });
        return new OcrPipelineResult(accepted, output);
    }

    private static void deduplicateOverlappingResults(List<OcrTextRegion> items) {
        for (int i = 0; i < items.size(); i++) {
            OcrTextRegion a = items.get(i);
            for (int j = items.size() - 1; j > i; j--) {
                OcrTextRegion b = items.get(j);
                if (a.getKind() != b.getKind()) continue;
                if (!normalize(a.getText()).equals(normalize(b.getText()))) continue;
                if (intersectionOverUnion(a.getScreenBounds(), b.getScreenBounds()) < 0.55f) continue;
                if (b.getConfidence() > a.getConfidence()) {
                    items.set(i, b);
                    a = b;
                }
                items.remove(j);
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static float intersectionOverUnion(Rect first, Rect second) {
        Rect intersection = new Rect(first);
        if (!intersection.intersect(second)) return 0f;
        long intersectionArea = (long) intersection.width() * intersection.height();
        long firstArea = (long) first.width() * first.height();
        long secondArea = (long) second.width() * second.height();
        long union = firstArea + secondArea - intersectionArea;
        return union <= 0 ? 0f : (float) intersectionArea / (float) union;
    }

    private static void checkThreshold(float value, String name) {
        if (!Float.isFinite(value) || value < 0f || value > 1f) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
    }
}
