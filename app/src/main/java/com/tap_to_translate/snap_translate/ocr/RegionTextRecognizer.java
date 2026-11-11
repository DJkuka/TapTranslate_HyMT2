package com.tap_to_translate.snap_translate.ocr;

import java.util.List;

/** One recognizer adapter, e.g. the current host OCR or a future PaddleOCR model. */
public interface RegionTextRecognizer {
    String getEngineId();
    List<RecognizedText> recognize(OcrFrame frame, DetectedRegion region) throws Exception;
}
