package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OcrPipelineResult {
    private final List<DetectedRegion> detectedRegions;
    private final List<OcrTextRegion> textRegions;

    OcrPipelineResult(List<DetectedRegion> detectedRegions, List<OcrTextRegion> textRegions) {
        this.detectedRegions = Collections.unmodifiableList(new ArrayList<>(detectedRegions));
        this.textRegions = Collections.unmodifiableList(new ArrayList<>(textRegions));
    }

    public List<DetectedRegion> getDetectedRegions() { return detectedRegions; }
    public List<OcrTextRegion> getTextRegions() { return textRegions; }
}
