package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.util.List;

/** Completion callback for the diagnostic/live Paddle OCR worker. */
public interface PaddleOcrCallback {
    void onComplete(List<PaddleOcrTextGroup> groups, Throwable error);
}