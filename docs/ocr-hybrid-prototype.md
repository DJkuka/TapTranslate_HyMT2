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

The RT-DETR bridge is wired into the real host OCR coordinator `vh.j`: `j(Bitmap)` starts detector inference on a copied screenshot while the original ML Kit path runs, and `d(Context,nf.d,Bitmap,boolean)` filters ML Kit blocks against detected `text_bubble` and `text_free` regions before the host's normal grouping/translation stage. On model failure, timeout, or zero matches, the original OCR result is retained. The APK build now verifies both call sites in the final decoded host DEX. The renderer is not yet changed: text-free blocks still use the host's original overlay style until the next renderer step. Real latency, memory and detector accuracy still need device testing.

## Next steps

1. Confirm build and the two live call-site checks.
2. On the Lenovo tablet, inspect Logcat tag `TapTranslateRTDETR` to confirm model initialization, detections and filtered block counts.
3. Tune overlap/thresholds against actual comic pages; add an opt-out fallback if needed.
4. Carry the detected class through the host block model so free text can use its own renderer.
5. Add the borderless rectangle fill using the reliable outer-ring colour sample.
6. Replace the recognizer independently when a better OCR engine is selected.

