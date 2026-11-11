package com.tap_to_translate.snap_translate.hymt2.ocr;

import java.util.Arrays;

/**
 * Estimates the colour immediately outside a replacement rectangle.
 *
 * It samples four disjoint strips outside the rectangle (not the text/rectangle itself),
 * quantizes RGB into a compact histogram, joins adjacent colour bins, then returns the
 * channel medians of the dominant cluster. The estimator has no Android dependency and
 * can be unit-tested with synthetic images.
 */
public final class OuterRingColorEstimator {
    public interface PixelReader {
        /** Returns ARGB for a pixel at x/y. */
        int getPixel(int x, int y);
    }

    private static final int CHANNEL_BINS = 32;
    private static final int HISTOGRAM_SIZE = CHANNEL_BINS * CHANNEL_BINS * CHANNEL_BINS;
    private static final int MAX_SAMPLES = 12000;
    private static final int MIN_SAMPLES = 16;
    private static final float MIN_DOMINANT_RATIO = 0.52f;

    private OuterRingColorEstimator() {}

    /**
     * @param rect rectangle which will be covered by the replacement fill
     * @param gap distance between the replacement rectangle and the sampled ring
     * @param thickness thickness of the sampled ring
     * @param pixelReader source image pixels
     */
    public static BackgroundSample estimate(
            int imageWidth, int imageHeight,
            int left, int top, int right, int bottom,
            int gap, int thickness, PixelReader pixelReader) {
        if (imageWidth <= 0 || imageHeight <= 0 || right <= left || bottom <= top
                || gap < 0 || thickness <= 0 || pixelReader == null) {
            throw new IllegalArgumentException("Invalid image, rectangle, ring or pixel reader");
        }

        int outerLeft = clamp((long) left - gap - thickness, 0, imageWidth);
        int outerTop = clamp((long) top - gap - thickness, 0, imageHeight);
        int outerRight = clamp((long) right + gap + thickness, 0, imageWidth);
        int outerBottom = clamp((long) bottom + gap + thickness, 0, imageHeight);
        int innerLeft = clamp((long) left - gap, 0, imageWidth);
        int innerTop = clamp((long) top - gap, 0, imageHeight);
        int innerRight = clamp((long) right + gap, 0, imageWidth);
        int innerBottom = clamp((long) bottom + gap, 0, imageHeight);

        long ringArea = (long) Math.max(0, outerRight - outerLeft)
                * Math.max(0, outerBottom - outerTop)
                - (long) Math.max(0, innerRight - innerLeft)
                * Math.max(0, innerBottom - innerTop);
        if (ringArea <= 0) return new BackgroundSample(0xff808080, 0, 0f, false);

        int stride = Math.max(1, (int) Math.ceil(Math.sqrt((double) ringArea / MAX_SAMPLES)));
        int[] histogram = new int[HISTOGRAM_SIZE];
        int[] sampleRed = new int[MAX_SAMPLES];
        int[] sampleGreen = new int[MAX_SAMPLES];
        int[] sampleBlue = new int[MAX_SAMPLES];
        int[] sampleBins = new int[MAX_SAMPLES];
        int count = 0;

        // Top and bottom strips span the full outer width.
        for (int y = outerTop; y < Math.min(innerTop, outerBottom); y += stride) {
            for (int x = outerLeft; x < outerRight; x += stride) {
                count = addPixel(pixelReader, x, y, histogram, redSums, greenSums,
                        blueSums, redValues, greenValues, blueValues, count);
            }
        }
        for (int y = Math.max(innerBottom, outerTop); y < outerBottom; y += stride) {
            for (int x = outerLeft; x < outerRight; x += stride) {
                count = addPixel(pixelReader, x, y, histogram, redSums, greenSums,
                        blueSums, redValues, greenValues, blueValues, count);
            }
        }
        // Side strips exclude the top/bottom bands, so corners are not counted twice.
        for (int y = Math.max(outerTop, innerTop); y < Math.min(innerBottom, outerBottom); y += stride) {
            for (int x = outerLeft; x < Math.min(innerLeft, outerRight); x += stride) {
                count = addPixel(pixelReader, x, y, histogram, redSums, greenSums,
                        blueSums, redValues, greenValues, blueValues, count);
            }
            for (int x = Math.max(innerRight, outerLeft); x < outerRight; x += stride) {
                count = addPixel(pixelReader, x, y, histogram, redSums, greenSums,
                        blueSums, redValues, greenValues, blueValues, count);
            }
        }

        if (count == 0) return new BackgroundSample(0xff808080, 0, 0f, false);

        int dominantBin = 0;
        for (int i = 1; i < histogram.length; i++) {
            if (histogram[i] > histogram[dominantBin]) dominantBin = i;
        }

        int dominantR = dominantBin / (CHANNEL_BINS * CHANNEL_BINS);
        int dominantG = (dominantBin / CHANNEL_BINS) % CHANNEL_BINS;
        int dominantB = dominantBin % CHANNEL_BINS;
        int clusterCount = 0;
        int[] clusterRed = new int[count];
        int[] clusterGreen = new int[count];
        int[] clusterBlue = new int[count];

        for (int i = 0; i < count; i++) {
            int bin = sampleBins[i];
            int r = bin / (CHANNEL_BINS * CHANNEL_BINS);
            int g = (bin / CHANNEL_BINS) % CHANNEL_BINS;
            int b = bin % CHANNEL_BINS;
            if (Math.abs(r - dominantR) <= 1 && Math.abs(g - dominantG) <= 1
                    && Math.abs(b - dominantB) <= 1) {
                clusterRed[clusterCount] = sampleRed[i];
                clusterGreen[clusterCount] = sampleGreen[i];
                clusterBlue[clusterCount] = sampleBlue[i];
                clusterCount++;
            }
        }

        if (clusterCount == 0) return new BackgroundSample(0xff808080, count, 0f, false);
        Arrays.sort(clusterRed, 0, clusterCount);
        Arrays.sort(clusterGreen, 0, clusterCount);
        Arrays.sort(clusterBlue, 0, clusterCount);
        int median = clusterCount / 2;
        int color = 0xff000000 | (clusterRed[median] << 16)
                | (clusterGreen[median] << 8) | clusterBlue[median];
        float confidence = (float) clusterCount / (float) count;
        boolean reliable = count >= MIN_SAMPLES && confidence >= MIN_DOMINANT_RATIO;
        return new BackgroundSample(color, count, confidence, reliable);
    }

    private static int addPixel(
            PixelReader reader, int x, int y,
            int[] histogram, int[] sampleRed, int[] sampleGreen, int[] sampleBlue,
            int[] sampleBins, int count) {
        if (count >= MAX_SAMPLES) return count;
        int color = reader.getPixel(x, y);
        int alpha = (color >>> 24) & 0xff;
        if (alpha < 224) return count;
        int r = (color >>> 16) & 0xff;
        int g = (color >>> 8) & 0xff;
        int b = color & 0xff;
        int bin = ((r >>> 3) * CHANNEL_BINS + (g >>> 3)) * CHANNEL_BINS + (b >>> 3);
        histogram[bin]++;
        sampleRed[count] = r;
        sampleGreen[count] = g;
        sampleBlue[count] = b;
        sampleBins[count] = bin;
        return count + 1;
    }

    private static int clamp(long value, int min, int max) {
        return (int) Math.max(min, Math.min((long) max, value));
    }
}
