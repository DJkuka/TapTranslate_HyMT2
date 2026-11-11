package com.tap_to_translate.snap_translate.hymt2.ocr;
import android.graphics.Bitmap;
public interface RtDetrInference extends AutoCloseable { RtDetrInferenceOutput run(Bitmap bitmap) throws Exception; void close(); }