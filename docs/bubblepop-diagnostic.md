# BubblePop diagnostic build

Experimental branch: `bubblepop-diagnostic`

This branch adds a standalone BubblePop ONNX contour-preview screen. It does not connect segmentation masks to the live OCR or translation overlay.

## Test procedure

1. Install the unsigned diagnostic APK alongside the normal installation only if the package/signature setup allows it; otherwise back up the normal app first.
2. Open OCR settings and choose **Открыть диагностику BubblePop**.
3. Select a comic page image.
4. Tap **Найти контуры баблов**.
5. Review the red outer contours and note the model-load plus inference time shown by the app.

The current diagnostic uses external contours from the model's binary mask. Touching speech balloons may appear merged; that result is useful feedback for the next instance-separation stage.

The upstream BubblePop model and its license are fetched at a pinned source commit during CI. No model weights are committed directly to this repository.
