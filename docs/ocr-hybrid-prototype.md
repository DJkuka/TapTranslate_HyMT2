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

The pipeline applies confidence filtering, clips all boxes to the visible bitmap, associates OCR boxes with their detected parent region by measuring the fraction of the text box covered by the parent region (rather than IoU against a much larger bubble), and suppresses duplicate same-text boxes with high IoU.

## Free-text overlay

The free-text overlay is a separate renderer behavior, not a recognizer responsibility.

The first independently testable component now exists:
- `OuterRingColorEstimator` samples four non-overlapping strips outside the replacement rectangle;
- it ignores transparent pixels, limits sampling work, quantizes RGB into a histogram, joins adjacent colour bins and takes channel medians from the dominant cluster;
- `BackgroundSample` reports the estimated colour, sample count, confidence and a reliability flag;
- `BitmapOuterRingSampler` adapts the estimator to Android Bitmap.

The caller must not use the fallback grey when `isReliable()` is false. The planned renderer sequence is:
1. retain the original screenshot bitmap until sampling finishes;
2. build a padded replacement rectangle from each free-text line;
3. sample outside that rectangle with a small gap, so text antialiasing and edge pixels do not contaminate the estimate;
4. use the sampled fill only when confidence is sufficient; otherwise preserve the original area or use a later, explicitly chosen fallback;
5. draw a square-corner filled rectangle with no stroke;
6. fit the translated text into that rectangle.

The estimator has synthetic JVM tests for flat backgrounds, local-vs-distant colour, mixed backgrounds, screen-edge clipping and the no-ring case. It is not yet connected to the live renderer.

The host already has a sophisticated colour estimator in `ca/c2.e(Bitmap, Rect)`, but a closer smali trace shows that its colour histogram is built primarily from pixels inside the supplied rectangle; neighbouring outside pixels are used to inspect edge contrast. It is therefore not the outer-ring sampler requested here and must not be reused as if it were one. We will implement a separate outer-ring sampler for free text, while leaving the host estimator unchanged for existing bubble rendering.

## YOLO output decoder

A model-independent decoder now handles the dual-label segmentation head shape used by the reference model: `[1, 38, 44436]` in channel-first order (4 box values, 2 class scores, 32 mask coefficients). It:
- maps class 0 to speech bubbles and class 1 to free text;
- rejects non-finite, invalid and low-confidence boxes;
- clips boxes to model input bounds;
- applies class-aware non-maximum suppression;
- preserves the 32 mask coefficients for a later contour/mask stage.

The decoder does not load a model or run inference yet. It is tested with synthetic tensors, including class separation and overlapping predictions.

## Current status

This branch now contains the neutral data model, region coordinator, outer-ring colour estimator and dual-label YOLO output decoder. It is not yet wired into `ScreenTranslateService` and does not yet change runtime OCR behavior. The build workflow runs JVM tests and checks that the classes compile into the helper APK and survive the merge into `classes5.dex`.

The reference dual-label model is about 21.4 MB and expects a 1472 × 1472 FLOAT32 RGB input. Its source repository is AGPL-3.0; model redistribution/licensing must be checked before bundling it into the app. We are not copying its inference implementation.

## Next implementation steps

1. Run the JVM tests for the outer-ring sampler and YOLO output decoder, then verify all new classes in the merged unsigned APK.
2. Implement the model runtime and detector adapter only after the model's redistribution terms are confirmed; measure latency and memory on tablet-class hardware.
3. Implement the first recognizer adapters. Keep the current OCR as a fallback, and use a Latin-only recognizer for free text.
4. Add a full-screen capture bridge, preserving bitmap-local and screen coordinate transforms.
5. Convert normalized regions to the existing host translation model while preserving one text region per translated overlay item.
6. Add a dedicated free-text draw branch: square-corner fill, no stroke, sampled outer-ring background.
7. Test ordinary full-screen mode first; then adapt crop-selection mode separately.
