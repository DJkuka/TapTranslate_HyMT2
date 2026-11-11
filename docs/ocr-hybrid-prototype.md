# Hybrid OCR prototype: RT-DETR-v2 detector

This branch is experimental; stable branch `vulkan-v12-integration` is unchanged.

## Detector model

- Source: https://huggingface.co/ogkalu/comic-text-and-bubble-detector
- Model: `detector-v4-s_int8.onnx`
- License declared upstream: Apache-2.0
- Size: 11,120,765 bytes
- SHA-256: `5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79`
- Input: 640x640 RGB, NCHW float32 normalized to [0,1], plus original target size [width,height].
- Outputs: labels, boxes, confidence scores.
- Labels: 0=bubble container, 1=text inside bubble, 2=free text outside bubbles.

Class 0 is deliberately excluded from OCR crops. Classes 1 and 2 become the common SPEECH_BUBBLE and FREE_TEXT regions. This avoids treating a whole balloon as a single text line.

## Runtime

The experimental helper uses `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` (MIT). The model is fetched by CI, checked against exact byte size and SHA-256, and packaged in helper assets. The APK merger copies both the `ai/onnxruntime` Java classes and ARM64 JNI libraries, as well as helper assets, into the final unsigned host APK. The merger verifies the model payload and native runtime libraries after packaging.

The Android adapter follows the reference implementation's preprocessing: resize to 640x640, pack RGB NCHW float32, and pass original dimensions separately. The reference implementation inspected is ciddwd/overlay-translator, specifically MangaBubbleDetectionDebugEngine and MangaBubbleInputWorkspace.

## OCR routing and geometry

`OcrRegionDetector` is runtime-neutral. RT-DETR detections are converted into `DetectedRegion`; the common `HybridOcrPipeline` routes bubble text to the bubble recognizer and free text to the free-text recognizer. The recognizer implementations remain replaceable, so a better OCR engine can be introduced later without changing the detector or renderer.

The existing OCR is intended as the initial bubble fallback. Free text will initially use ML Kit Latin. Russian/Ukrainian recognition is not needed because OCR only reads the comic's source lettering; Hy-MT2 performs translation.

## Free-text replacement

`OuterRingColorEstimator` samples a narrow external ring around the replacement rectangle. The planned renderer draws a square-corner solid rectangle without a border, using the dominant nearby background colour only when the sample is reliable, then fits the translated text inside it. Complex backgrounds must not be blindly painted with an unreliable averaged colour.

## Current boundary

The RT-DETR inference adapter, output decoder, model provider, and APK packaging checks are being integrated. This does not yet wire the detector into the live `ScreenTranslateService`, does not yet replace the host OCR route, and does not yet change the visible overlay. A successful helper build is only a build/packaging milestone; real inference latency, memory, detection quality, and coordinate alignment must still be tested on the Lenovo tablet.

## Next steps

1. Verify helper compilation and unit tests.
2. Verify final APK includes ONNX Runtime classes, both JNI libraries and the exact model asset.
3. Add a debug-only detector invocation on a captured bitmap, with class/confidence/coordinates/timing diagnostics.
4. Validate boxes against real comic pages and tune thresholds.
5. Connect text_bubble to the current OCR fallback and text_free to ML Kit Latin.
6. Preserve geometry through Hy-MT2 and add the no-border outer-ring fill renderer.
7. Measure CPU latency and peak memory on the Snapdragon 680 before testing NNAPI.

