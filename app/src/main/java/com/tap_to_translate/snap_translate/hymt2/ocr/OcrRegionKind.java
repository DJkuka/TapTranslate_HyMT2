package com.tap_to_translate.snap_translate.hymt2.ocr;

/** Semantic class of a detected comic text region. */
public enum OcrRegionKind {
    BUBBLE_CONTAINER,
    /** Page-wide BubblePop interior mask; not an OCR grouping identity. */
    PAGE_BUBBLE_MASK,
    SPEECH_BUBBLE,
    FREE_TEXT
}
