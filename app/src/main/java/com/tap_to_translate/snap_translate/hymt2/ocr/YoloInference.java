package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.graphics.Bitmap;

/** Runtime-specific adapter for a dual-label YOLO model (TFLite, LiteRT, ONNX, etc.). */
public interface YoloInference extends AutoCloseable {
    int getInputWidth();
    int getInputHeight();
    YoloInferenceOutput run(Bitmap normalizedRgbInput) throws Exception;
    @Override void close();
}
