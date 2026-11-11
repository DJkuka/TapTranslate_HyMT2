package com.tap_to_translate.snap_translate.ocr;

import java.util.List;

/** YOLO or another detector can implement this without coupling the rest of the app to it. */
public interface OcrRegionDetector {
    List<DetectedRegion> detect(OcrFrame frame) throws Exception;
}
