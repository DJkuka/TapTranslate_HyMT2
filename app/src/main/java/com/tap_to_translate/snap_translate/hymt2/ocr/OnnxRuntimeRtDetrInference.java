package com.tap_to_translate.snap_translate.hymt2.ocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class OnnxRuntimeRtDetrInference implements RtDetrInference {
    private static final String PERF_TAG = "TapTranslatePerf";
    private static final int SIDE = 640;
    private static final int PIXELS = SIDE * SIDE;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String imageName;
    private final String sizeName;
    private final int[] pixels = new int[PIXELS];
    private final float[] values = new float[PIXELS * 3];
    private final FloatBuffer image = ByteBuffer.allocateDirect(PIXELS * 12)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final LongBuffer size = ByteBuffer.allocateDirect(16)
            .order(ByteOrder.nativeOrder()).asLongBuffer();
    private boolean closed;

    public OnnxRuntimeRtDetrInference(File model) throws OrtException {
        long initStartNs = SystemClock.elapsedRealtimeNanos();
        if (model == null || !model.isFile()) throw new IllegalArgumentException("RT-DETR model missing");
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
            options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(model.getAbsolutePath(), options);
        } finally {
            options.close();
        }

        Set<String> names = session.getInputNames();
        if (names.size() < 2) {
            session.close();
            throw new IllegalStateException("RT-DETR requires two inputs");
        }
        imageName = names.contains("images") ? "images" : names.iterator().next();
        sizeName = names.contains("orig_target_sizes") ? "orig_target_sizes"
                : names.stream().filter(name -> !name.equals(imageName)).findFirst().orElseThrow();

        long initMs = (SystemClock.elapsedRealtimeNanos() - initStartNs) / 1_000_000L;
        Log.i(PERF_TAG, "event=rtdetr_session_ready init_ms=" + initMs
                + " model_bytes=" + model.length()
                + " ort_threads=" + Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
    }

    @Override
    public synchronized RtDetrInferenceOutput run(Bitmap source) throws Exception {
        if (closed) throw new IllegalStateException("closed");
        if (source == null || source.isRecycled()) throw new IllegalArgumentException("bitmap");
        String frameId = Integer.toHexString(System.identityHashCode(source));

        long preprocessStartNs = SystemClock.elapsedRealtimeNanos();
        Bitmap scaled = source.getWidth() == SIDE && source.getHeight() == SIDE
                ? source : Bitmap.createScaledBitmap(source, SIDE, SIDE, true);
        try {
            scaled.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE);
            for (int i = 0; i < PIXELS; i++) {
                int color = pixels[i];
                values[i] = ((color >> 16) & 255) / 255f;
                values[PIXELS + i] = ((color >> 8) & 255) / 255f;
                values[2 * PIXELS + i] = (color & 255) / 255f;
            }
            image.clear();
            image.put(values);
            image.flip();
            size.clear();
            size.put(source.getWidth()).put(source.getHeight());
            size.flip();
            long preprocessMs = (SystemClock.elapsedRealtimeNanos() - preprocessStartNs) / 1_000_000L;

            long tensorStartNs = SystemClock.elapsedRealtimeNanos();
            try (OnnxTensor imageTensor = OnnxTensor.createTensor(env, image, new long[]{1, 3, SIDE, SIDE});
                 OnnxTensor sizeTensor = OnnxTensor.createTensor(env, size, new long[]{1, 2})) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put(imageName, imageTensor);
                inputs.put(sizeName, sizeTensor);
                long tensorCreateMs = (SystemClock.elapsedRealtimeNanos() - tensorStartNs) / 1_000_000L;

                long inferenceStartNs = SystemClock.elapsedRealtimeNanos();
                try (OrtSession.Result result = session.run(inputs)) {
                    long inferenceMs = (SystemClock.elapsedRealtimeNanos() - inferenceStartNs) / 1_000_000L;
                    if (result.size() < 3) throw new IllegalStateException("RT-DETR outputs missing");

                    long extractionStartNs = SystemClock.elapsedRealtimeNanos();
                    long[] labels = labels(result.get(0).getValue());
                    float[][] boxes = boxes(result.get(1).getValue());
                    float[] scores = scores(result.get(2).getValue());
                    RtDetrInferenceOutput output = new RtDetrInferenceOutput(labels, boxes, scores);
                    long extractionMs = (SystemClock.elapsedRealtimeNanos() - extractionStartNs) / 1_000_000L;

                    Log.i(PERF_TAG, "event=rtdetr_onnx frame=" + frameId
                            + " input=" + source.getWidth() + "x" + source.getHeight()
                            + " resize_normalize_ms=" + preprocessMs
                            + " tensor_create_ms=" + tensorCreateMs
                            + " ort_inference_ms=" + inferenceMs
                            + " output_extract_ms=" + extractionMs
                            + " raw_outputs=" + labels.length);
                    return output;
                }
            }
        } finally {
            if (scaled != source && !scaled.isRecycled()) scaled.recycle();
        }
    }

    private static long[] labels(Object value) {
        if (value instanceof long[]) return (long[]) value;
        if (value instanceof long[][]) {
            long[][] array = (long[][]) value;
            if (array.length > 0) return array[0];
        }
        throw new IllegalStateException("Unexpected labels tensor " + value.getClass());
    }

    private static float[][] boxes(Object value) {
        if (value instanceof float[][][]) return ((float[][][]) value)[0];
        if (value instanceof float[][]) return (float[][]) value;
        throw new IllegalStateException("Unexpected boxes tensor " + value.getClass());
    }

    private static float[] scores(Object value) {
        if (value instanceof float[]) return (float[]) value;
        if (value instanceof float[][]) {
            float[][] array = (float[][]) value;
            if (array.length > 0) return array[0];
        }
        throw new IllegalStateException("Unexpected scores tensor " + value.getClass());
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            try {
                session.close();
            } catch (OrtException ignored) {
            }
        }
    }
}
