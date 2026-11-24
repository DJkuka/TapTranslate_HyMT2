package com.tap_to_translate.snap_translate.hymt2.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.paddle.ocr.util.OpenCVUtils;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;

/**
 * Produces the page-wide BubblePop interior mask. This intentionally does not
 * split connected masks into individual balloons: OCR groups remain independent
 * and are constrained by this shared page mask plus their existing OCR container.
 */
public final class BubblePopPageMaskDetector implements AutoCloseable {
    private static final String TAG = "TapTranslateBubbleMask";
    private static final String MODEL_ASSET = "models/bubblepop/model_fp16.onnx";
    private final BubblePopSegmenter segmenter;

    public BubblePopPageMaskDetector(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("Context is required");
        if (!OpenCVUtils.INSTANCE.init(context)) {
            throw new IllegalStateException("OpenCV initialization failed");
        }
        File model = new File(context.getCodeCacheDir(), "bubblepop_page_mask_fp16.onnx");
        if (!model.exists() || model.length() < 100_000L) {
            File temporary = new File(model.getParentFile(), model.getName() + ".tmp");
            try (InputStream input = context.getAssets().open(MODEL_ASSET);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
                if (model.exists() && !model.delete()) {
                    throw new IllegalStateException("Cannot replace cached BubblePop model");
                }
                if (!temporary.renameTo(model)) {
                    throw new IllegalStateException("Cannot install BubblePop model");
                }
            } finally {
                if (temporary.exists()) temporary.delete();
            }
        }
        segmenter = new BubblePopSegmenter(model.getAbsolutePath());
    }

    /** Returns one full-page mask region. Caller owns only the returned byte array. */
    public DetectedRegion detect(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        Mat image = new Mat();
        BubblePopSegmenter.SegmentationResult result = null;
        try {
            Utils.bitmapToMat(bitmap, image);
            result = segmenter.getSegmentation(image);
            Mat mask = result.mask;
            if (mask.empty() || mask.cols() != bitmap.getWidth()
                    || mask.rows() != bitmap.getHeight()) {
                Log.w(TAG, "BubblePop returned an empty or incorrectly sized page mask");
                return null;
            }
            byte[] pixels = new byte[bitmap.getWidth() * bitmap.getHeight()];
            mask.get(0, 0, pixels);
            int foreground = 0;
            for (byte pixel : pixels) if ((pixel & 0xff) != 0) foreground++;
            if (foreground < 100) {
                Log.w(TAG, "BubblePop page mask is nearly empty; ignoring it");
                return null;
            }
            Log.i(TAG, "page_mask=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " foreground=" + foreground + " split=false");
            return new DetectedRegion(
                    "bubblepop:page-mask",
                    OcrRegionKind.PAGE_BUBBLE_MASK,
                    new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                    1.0f, null, null, pixels, bitmap.getWidth(), bitmap.getHeight());
        } catch (Throwable error) {
            Log.e(TAG, "BubblePop page mask failed; OCR and rectangular fallback remain active", error);
            return null;
        } finally {
            if (result != null) result.release();
            image.release();
        }
    }

    /** Execute a real segmentation pass for the OCR diagnostics screen. */
    public String diagnostic(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("BubblePop diagnostic image unavailable");
        Mat image = new Mat();
        BubblePopSegmenter.SegmentationResult result = null;
        try {
            Utils.bitmapToMat(bitmap, image);
            result = segmenter.getSegmentation(image);
            long maskPixels = org.opencv.core.Core.countNonZero(result.mask);
            long boundaryPixels = org.opencv.core.Core.countNonZero(result.boundary);
            long total = (long) bitmap.getWidth() * bitmap.getHeight();
            return "PASS: ONNX-модель загружена, инференс выполнен; mask_pixels=" + maskPixels + "/" + total
                    + "; boundary_pixels=" + boundaryPixels + "; image=" + bitmap.getWidth() + "x" + bitmap.getHeight();
        } catch (Throwable error) {
            throw new IllegalStateException("BubblePop inference failed: " + error.getMessage(), error);
        } finally {
            if (result != null) result.release();
            image.release();
        }
    }

    @Override public void close() {
        segmenter.close();
    }
}
