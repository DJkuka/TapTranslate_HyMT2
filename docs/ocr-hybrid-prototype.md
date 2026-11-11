# Hybrid OCR prototype: first integration layer

This branch is experimental and does not change the stable `vulkan-v12-integration` branch.

## Purpose

The first layer establishes an OCR-engine-neutral contract. It deliberately does not bundle or initialize YOLO, ML Kit, Tesseract, or PaddleOCR yet. The current APK's OCR and translation path remains the fallback until detector and recognizer adapters are wired into the real capture service.

## Data flow

`OcrFrame` -> `OcrRegionDetector` -> `DetectedRegion` -> `RegionTextRecognizer` -> `RecognizedText` -> `OcrTextRegion` -> existing translation / overlay bridge.

Two semantic classes are fixed:

- `SPEECH_BUBBLE`
- `FREE_TEXT`

Each normalized text region keeps:
- stable region and parent-region IDs;
- original bitmap-local rectangle;
- full-screen physical-pixel rectangle;
- recognizer ID and confidence;
- raw recognized text.

This makes OCR engines replaceable without changing translation or drawing code.

## Coordinate rules

All detector and recognizer coordinates are local to the exact bitmap passed to the pipeline. The `OcrFrame` owns the transform to full-screen physical pixels. A cropped screenshot must provide its screen origin; a scaled frame must provide the scale. Coordinates must never be reconstructed from display density alone.

The existing app has a separate crop-selection path. It currently crops a bitmap before OCR and later flattens results to plain text. That path must be adapted explicitly before region overlays can be enabled for selection mode. The initial live integration should target full-screen mode first.

## OCR routing

- Speech bubbles: initially route to an adapter around the current on-device OCR result path.
- Free text: initially route to a Latin-script recognizer adapter.
- Later: replace either adapter with a specialized comic OCR engine without changing the detector or normalized result model.

The pipeline applies confidence filtering, clips all boxes to the visible bitmap, associates OCR boxes with their detected parent region using geometric overlap, and suppresses duplicate same-text boxes with high IoU.

## Free-text overlay

The free-text overlay is a separate renderer behavior, not a recognizer responsibility:

1. retain the original screenshot bitmap until the background colour is sampled;
2. build an expanded, padded replacement rectangle from the text line bounds;
3. sample a narrow outer ring, excluding the text rectangle;
4. estimate the dominant/robust colour and assess local variation;
5. draw a plain filled rectangle with no stroke or border;
6. fit the translated text into that rectangle.

The host already has a sophisticated background-colour estimator in `ca/c2.e(Bitmap, Rect)`. It samples pixels outside the target rectangle, estimates dominant colour and local variation. This is a useful starting point, but it is currently used by the bubble-style path. The free-text route must call it deliberately and must retain the original frame for sampling.

## Current status

This commit contains the neutral data model and pipeline coordinator only. It is not yet wired into `ScreenTranslateService` and does not yet change runtime OCR behavior. The build workflow checks that the nine classes compile into the helper APK and survive the merge into `classes5.dex`.

## Next implementation steps

1. Build and verify the contract classes in the merged unsigned APK.
2. Implement a detector adapter and test the chosen dual-label YOLO model on tablet-class hardware.
3. Implement the first recognizer adapters. Keep the current OCR as a fallback, and use a Latin-only recognizer for free text.
4. Add a full-screen capture bridge, preserving bitmap-local and screen coordinate transforms.
5. Convert normalized regions to the existing host translation model while preserving one text region per translated overlay item.
6. Add a dedicated free-text draw branch: square-corner fill, no stroke, sampled outer-ring background.
7. Test ordinary full-screen mode first; then adapt crop-selection mode separately.
