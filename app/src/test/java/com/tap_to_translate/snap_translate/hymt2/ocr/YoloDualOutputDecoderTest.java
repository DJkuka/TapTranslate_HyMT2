package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.nio.FloatBuffer;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class YoloDualOutputDecoderTest {
    @Test public void decodesBothSemanticClassesAndOrdersByConfidence() {
        FloatBuffer tensor = tensor(2);
        setDetection(tensor, 2, 0, 0.5f, 0.5f, 0.4f, 0.3f, 0.91f, 0.05f);
        setDetection(tensor, 2, 1, 0.25f, 0.35f, 0.2f, 0.2f, 0.10f, 0.88f);

        List<YoloDetection> result = YoloDualOutputDecoder.decode(
                tensor, 2, 1472, 1472, 0.30f, 0.45f);

        assertEquals(2, result.size());
        assertEquals(OcrRegionKind.SPEECH_BUBBLE, result.get(0).getKind());
        assertEquals(OcrRegionKind.FREE_TEXT, result.get(1).getKind());
        assertTrue(result.get(0).getConfidence() > result.get(1).getConfidence());
        assertEquals(1472f * 0.3f, result.get(0).getLeft(), 0.01f);
    }

    @Test public void suppressesOverlappingBoxesOfSameClassOnly() {
        FloatBuffer tensor = tensor(3);
        setDetection(tensor, 3, 0, 0.5f, 0.5f, 0.4f, 0.4f, 0.95f, 0.01f);
        setDetection(tensor, 3, 1, 0.51f, 0.5f, 0.4f, 0.4f, 0.80f, 0.01f);
        setDetection(tensor, 3, 2, 0.5f, 0.5f, 0.4f, 0.4f, 0.01f, 0.85f);

        List<YoloDetection> result = YoloDualOutputDecoder.decode(
                tensor, 3, 1472, 1472, 0.30f, 0.45f);

        assertEquals(2, result.size());
        assertEquals(OcrRegionKind.SPEECH_BUBBLE, result.get(0).getKind());
        assertEquals(OcrRegionKind.FREE_TEXT, result.get(1).getKind());
    }

    @Test public void rejectsInvalidAndBelowThresholdDetections() {
        FloatBuffer tensor = tensor(3);
        setDetection(tensor, 3, 0, 0.5f, 0.5f, 0.4f, 0.4f, 0.20f, 0.10f);
        setDetection(tensor, 3, 1, Float.NaN, 0.5f, 0.4f, 0.4f, 0.90f, 0.10f);
        setDetection(tensor, 3, 2, 0.5f, 0.5f, -0.4f, 0.4f, 0.90f, 0.10f);

        List<YoloDetection> result = YoloDualOutputDecoder.decode(
                tensor, 3, 1472, 1472, 0.30f, 0.45f);
        assertTrue(result.isEmpty());
    }

    private static FloatBuffer tensor(int anchors) {
        return FloatBuffer.allocate(YoloDualOutputDecoder.FEATURE_COUNT * anchors);
    }

    private static void setDetection(FloatBuffer tensor, int anchors, int anchor,
                                     float cx, float cy, float width, float height,
                                     float bubbleScore, float textScore) {
        put(tensor, anchors, anchor, 0, cx);
        put(tensor, anchors, anchor, 1, cy);
        put(tensor, anchors, anchor, 2, width);
        put(tensor, anchors, anchor, 3, height);
        put(tensor, anchors, anchor, 4, bubbleScore);
        put(tensor, anchors, anchor, 5, textScore);
        for (int i = 0; i < 32; i++) put(tensor, anchors, anchor, 6 + i, 0.01f);
    }

    private static void put(FloatBuffer tensor, int anchors, int anchor, int channel, float value) {
        tensor.put(channel * anchors + anchor, value);
    }
}
