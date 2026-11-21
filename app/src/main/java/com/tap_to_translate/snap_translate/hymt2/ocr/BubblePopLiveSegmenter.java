package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.paddle.ocr.util.OpenCVUtils;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional live BubblePop segmentation adapter.
 *
 * Produces one BUBBLE_CONTAINER per retained instance. Existing RT-DETR text
 * and free-text detections are kept by the caller. Any failure is isolated so
 * the normal OCR route can continue unchanged.
 */
public final class BubblePopLiveSegmenter implements AutoCloseable {
    private static final String TAG = "TapTranslateBubblePop";
    private static final String MODEL_ASSET = "models/bubblepop/model_fp16.onnx";

    private final BubblePopSegmenter segmenter;

    public BubblePopLiveSegmenter(Context context) throws Exception {
        if (!OpenCVUtils.init(context)) {
            throw new IllegalStateException("OpenCV initialization failed");
        }
        File model = new File(context.getCodeCacheDir(), "bubblepop_model_fp16.onnx");
        if (!model.exists() || model.length() < 100_000L) {
            File temp = new File(model.getParentFile(), model.getName() + ".tmp");
            try {
                context.getAssets().open(MODEL_ASSET).use(input -> {
                    try (FileOutputStream output = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                });
                if (model.exists() && !model.delete()) {
                    throw new IllegalStateException("Cannot replace cached BubblePop model");
                }
                if (!temp.renameTo(model)) throw new IllegalStateException("Cannot install cached BubblePop model");
            } finally {
                if (temp.exists()) temp.delete();
            }
        }
        segmenter = new BubblePopSegmenter(model.getAbsolutePath());
    }

    public List<DetectedRegion> detect(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return new ArrayList<>();
        Mat image = new Mat();
        BubblePopSegmenter.SegmentationResult segmentation = null;
        BubblePopBoundarySplitter.Result split = null;
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        ArrayList<DetectedRegion> output = new ArrayList<>();
        try {
            Utils.bitmapToMat(bitmap, image);
            segmentation = segmenter.getSegmentation(image);
            split = BubblePopBoundarySplitter.split(
                    segmentation.mask, segmentation.boundary);
            int count = Imgproc.connectedComponentsWithStats(
                    split.instances, labels, stats, centroids, 8, org.opencv.core.CvType.CV_32S);
            int id = 0;
            for (int instance = 1; instance < count; instance++) {
                int area = (int) stats.get(instance, Imgproc.CC_STAT_AREA)[0];
                if (area < 100) continue;
                int x = (int) stats.get(instance, Imgproc.CC_STAT_LEFT)[0];
                int y = (int) stats.get(instance, Imgproc.CC_STAT_TOP)[0];
                int width = (int) stats.get(instance, Imgproc.CC_STAT_WIDTH)[0];
                int height = (int) stats.get(instance, Imgproc.CC_STAT_HEIGHT)[0];
                if (width <= 1 || height <= 1) continue;
                output.add(new DetectedRegion(
                        "bubblepop:" + (id++), OcrRegionKind.BUBBLE_CONTAINER,
                        new Rect(x, y, x + width, y + height), 1.0f, null));
            }
            Log.i(TAG, "live segmentation: input_components=" + split.inputComponents
                    + " instances=" + output.size() + " split_components=" + split.splitComponents
                    + " boundary_accepted=" + split.boundaryAcceptedCount
                    + " geometry={" + split.geometrySummary + "}");
            return output;
        } catch (Throwable error) {
            Log.e(TAG, "BubblePop live segmentation failed; RT-DETR containers will be retained", error);
            return output;
        } finally {
            labels.release();
            stats.release();
            centroids.release();
            if (split != null) {
                split.instances.release();
                split.mask.release();
            }
            if (segmentation != null) segmentation.release();
            image.release();
        }
    }

    @Override public void close() {
        segmenter.close();
    }
}
