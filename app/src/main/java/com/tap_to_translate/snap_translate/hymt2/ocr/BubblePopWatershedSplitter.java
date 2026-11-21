package com.tap_to_translate.snap_translate.hymt2.ocr;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Experimental instance splitter for touching BubblePop regions.
 *
 * This class is diagnostic-only. It does not alter BubblePopSegmenter's
 * production mask or the live translation pipeline.
 *
 * Strategy:
 *  1. Find separate connected components in BubblePop's binary mask.
 *  2. Inside large components, detect separated peaks of the distance transform.
 *  3. Keep only peak pairs with a narrow saddle and either strong boundary-head
 *     evidence or a very narrow geometric connection.
 *  4. Use those peaks as markers for OpenCV watershed. The watershed surface
 *     combines the original grayscale artwork and BubblePop's boundary map.
 *  5. Accept a split only when at least two substantial foreground regions remain.
 *
 * The thresholds are intentionally conservative. A component that cannot be
 * split confidently is copied unchanged to the output.
 */
public final class BubblePopWatershedSplitter {
    private static final int MIN_COMPONENT_AREA = 700;
    private static final int MIN_OUTPUT_REGION_AREA = 100;
    private static final double MIN_PEAK_RADIUS = 5.0;
    private static final int PEAK_NEIGHBORHOOD = 15;
    private static final int MAX_PEAKS = 8;
    private static final double MIN_PEAK_DISTANCE = 10.0;
    private static final double STRONG_SADDLE_RATIO = 0.62;
    private static final double BOUNDARY_SADDLE_RATIO = 0.82;
    private static final int BOUNDARY_EVIDENCE = 125;
    private static final int ROI_PADDING = 8;

    private BubblePopWatershedSplitter() {}

    public static final class Result {
        public final Mat mask;
        public final Mat instances;
        public final int instanceCount;
        public final int inputComponents;
        public final int outputComponents;
        public final int splitComponents;

        private Result(Mat mask, Mat instances, int instanceCount,
                       int inputComponents, int outputComponents, int splitComponents) {
            this.mask = mask;
            this.instances = instances;
            this.instanceCount = instanceCount;
            this.inputComponents = inputComponents;
            this.outputComponents = outputComponents;
            this.splitComponents = splitComponents;
        }
    }

    /** Caller must release result.mask. */
    public static Result split(Mat source, Mat inputMask, Mat boundaryMap) {
        if (source == null || inputMask == null || boundaryMap == null
                || source.empty() || inputMask.empty() || boundaryMap.empty()) {
            throw new IllegalArgumentException("source, mask and boundary map must be non-empty");
        }
        if (source.rows() != inputMask.rows() || source.cols() != inputMask.cols()
                || source.rows() != boundaryMap.rows() || source.cols() != boundaryMap.cols()) {
            throw new IllegalArgumentException("source, mask and boundary map dimensions differ");
        }

        Mat binary = new Mat();
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        Mat output = Mat.zeros(inputMask.size(), CvType.CV_8UC1);
        Mat instances = Mat.zeros(inputMask.size(), CvType.CV_32SC1);
        int inputCount = 0;
        int splitCount = 0;
        int nextInstanceId = 1;
        try {
            if (inputMask.channels() == 1) {
                inputMask.convertTo(binary, CvType.CV_8UC1);
            } else {
                Mat grayMask = new Mat();
                Imgproc.cvtColor(inputMask, grayMask, Imgproc.COLOR_BGR2GRAY);
                grayMask.convertTo(binary, CvType.CV_8UC1);
                grayMask.release();
            }
            Imgproc.threshold(binary, binary, 0, 255, Imgproc.THRESH_BINARY);
            inputCount = Imgproc.connectedComponentsWithStats(
                    binary, labels, stats, centroids, 8, CvType.CV_32S);

            for (int id = 1; id < inputCount; id++) {
                int area = (int) stats.get(id, Imgproc.CC_STAT_AREA)[0];
                int x = (int) stats.get(id, Imgproc.CC_STAT_LEFT)[0];
                int y = (int) stats.get(id, Imgproc.CC_STAT_TOP)[0];
                int w = (int) stats.get(id, Imgproc.CC_STAT_WIDTH)[0];
                int h = (int) stats.get(id, Imgproc.CC_STAT_HEIGHT)[0];
                Rect rect = paddedRect(x, y, w, h, ROI_PADDING, binary.cols(), binary.rows());

                Mat labelRoi = labels.submat(rect);
                Mat component = new Mat();
                Core.compare(labelRoi, new Scalar(id), component, Core.CMP_EQ);
                labelRoi.release();

                int producedInstances = 0;
                if (area >= MIN_COMPONENT_AREA) {
                    producedInstances = trySplitComponent(
                            source, boundaryMap, component, rect, output, instances, nextInstanceId);
                }
                if (producedInstances > 1) {
                    splitCount++;
                    nextInstanceId += producedInstances;
                } else {
                    Mat outputRoi = output.submat(rect);
                    Core.bitwise_or(outputRoi, component, outputRoi);
                    outputRoi.release();
                    Mat instanceRoi = instances.submat(rect);
                    instanceRoi.setTo(new Scalar(nextInstanceId), component);
                    instanceRoi.release();
                    nextInstanceId++;
                }
                component.release();
            }

            int outputCount = countForegroundComponents(output);
            return new Result(output, instances, nextInstanceId - 1, inputCount - 1, outputCount - 1, splitCount);
        } catch (Throwable t) {
            output.release();
            instances.release();
            throw t;
        } finally {
            binary.release();
            labels.release();
            stats.release();
            centroids.release();
        }
    }

    private static int trySplitComponent(
            Mat source, Mat boundaryMap, Mat component, Rect rect,
            Mat output, Mat instances, int firstInstanceId) {
        Mat distance = new Mat();
        Mat dilated = new Mat();
        Mat peakMask = new Mat();
        Mat radiusMask = new Mat();
        Mat peakLabels = new Mat();
        Mat peakStats = new Mat();
        Mat peakCentroids = new Mat();
        try {
            Imgproc.distanceTransform(component, distance, Imgproc.DIST_L2, 5);
            Mat kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, new Size(PEAK_NEIGHBORHOOD, PEAK_NEIGHBORHOOD));
            Imgproc.dilate(distance, dilated, kernel);
            kernel.release();

            Core.compare(distance, dilated, peakMask, Core.CMP_GE);
            Core.compare(distance, new Scalar(MIN_PEAK_RADIUS), radiusMask, Core.CMP_GE);
            Core.bitwise_and(peakMask, radiusMask, peakMask);

            int peakCount = Imgproc.connectedComponentsWithStats(
                    peakMask, peakLabels, peakStats, peakCentroids, 8, CvType.CV_32S);
            if (peakCount <= 2) {
                return 0;
            }

            List<Peak> peaks = collectPeaks(distance, peakLabels, peakCount);
            peaks = suppressNearbyPeaks(peaks);
            if (peaks.size() < 2) {
                return 0;
            }

            Mat boundaryRoi = boundaryMap.submat(rect);
            float[] distanceData = new float[distance.rows() * distance.cols()];
            distance.get(0, 0, distanceData);
            byte[] boundaryData = new byte[distanceData.length];
            boundaryRoi.get(0, 0, boundaryData);
            boundaryRoi.release();

            List<Peak> seeds = selectSeparatedPeaks(peaks, distanceData, boundaryData,
                    distance.cols(), distance.rows());
            if (seeds.size() < 2) {
                return false;
            }

            Mat markers = new Mat(component.rows(), component.cols(), CvType.CV_32SC1, new Scalar(0));
            Mat background = new Mat();
            Core.bitwise_not(component, background);
            markers.setTo(new Scalar(1), background);
            background.release();

            for (int i = 0; i < seeds.size(); i++) {
                Peak peak = seeds.get(i);
                int radius = Math.max(2, Math.min(4, Math.round(peak.radius * 0.18f)));
                Imgproc.circle(markers, new Point(peak.x, peak.y), radius,
                        new Scalar(i + 2), -1, Imgproc.LINE_8, 0);
            }

            Mat watershedImage = buildWatershedImage(source, boundaryMap, rect);
            try {
                Imgproc.watershed(watershedImage, markers);
            } finally {
                watershedImage.release();
            }

            int[] markerData = new int[component.rows() * component.cols()];
            markers.get(0, 0, markerData);
            markers.release();

            int[] regionAreas = new int[seeds.size() + 2];
            byte[] componentData = new byte[markerData.length];
            component.get(0, 0, componentData);
            for (int i = 0; i < markerData.length; i++) {
                if ((componentData[i] & 0xFF) != 0) {
                    int label = markerData[i];
                    if (label >= 2 && label < regionAreas.length) {
                        regionAreas[label]++;
                    }
                }
            }

            int minRegionArea = Math.max(MIN_OUTPUT_REGION_AREA,
                    (int) (Core.countNonZero(component) * 0.035));
            int substantialRegions = 0;
            for (int label = 2; label < regionAreas.length; label++) {
                if (regionAreas[label] >= minRegionArea) {
                    substantialRegions++;
                }
            }
            if (substantialRegions < 2) {
                return false;
            }

            // Preserve instance IDs until contour extraction. A binary union here
            // can merge regions again when they touch diagonally.
            int[] labelToInstance = new int[regionAreas.length];
            int acceptedRegions = 0;
            for (int label = 2; label < regionAreas.length; label++) {
                if (regionAreas[label] >= minRegionArea) {
                    labelToInstance[label] = firstInstanceId + acceptedRegions;
                    acceptedRegions++;
                }
            }
            if (acceptedRegions < 2) return 0;

            byte[] splitData = new byte[markerData.length];
            int[] instanceData = new int[markerData.length];
            for (int i = 0; i < markerData.length; i++) {
                int label = markerData[i];
                if ((componentData[i] & 0xFF) != 0
                        && label >= 2 && label < labelToInstance.length
                        && labelToInstance[label] != 0) {
                    splitData[i] = (byte) 255;
                    instanceData[i] = labelToInstance[label];
                }
            }

            Mat splitLocal = new Mat(component.rows(), component.cols(), CvType.CV_8UC1);
            splitLocal.put(0, 0, splitData);
            Mat instanceLocal = new Mat(component.rows(), component.cols(), CvType.CV_32SC1);
            instanceLocal.put(0, 0, instanceData);

            Mat outputRoi = output.submat(rect);
            splitLocal.copyTo(outputRoi);
            outputRoi.release();
            Mat instanceRoi = instances.submat(rect);
            instanceLocal.copyTo(instanceRoi);
            instanceRoi.release();
            splitLocal.release();
            instanceLocal.release();
            System.out.println("BubblePop watershed: component split into "
                    + acceptedRegions + " labeled regions from " + seeds.size() + " markers");
            return acceptedRegions;
        } finally {
            distance.release();
            dilated.release();
            peakMask.release();
            radiusMask.release();
            peakLabels.release();
            peakStats.release();
            peakCentroids.release();
        }
    }

    private static List<Peak> collectPeaks(Mat distance, Mat peakLabels, int count) {
        int total = distance.rows() * distance.cols();
        float[] d = new float[total];
        int[] ids = new int[total];
        distance.get(0, 0, d);
        peakLabels.get(0, 0, ids);

        float[] maxRadius = new float[count];
        int[] maxIndex = new int[count];
        for (int i = 0; i < maxIndex.length; i++) maxIndex[i] = -1;
        for (int i = 0; i < total; i++) {
            int id = ids[i];
            if (id > 0 && id < count && d[i] > maxRadius[id]) {
                maxRadius[id] = d[i];
                maxIndex[id] = i;
            }
        }

        List<Peak> peaks = new ArrayList<>();
        int width = distance.cols();
        for (int id = 1; id < count; id++) {
            int index = maxIndex[id];
            if (index < 0 || maxRadius[id] < MIN_PEAK_RADIUS) continue;
            peaks.add(new Peak(index % width, index / width, maxRadius[id]));
        }
        Collections.sort(peaks, new Comparator<Peak>() {
            @Override public int compare(Peak a, Peak b) {
                return Float.compare(b.radius, a.radius);
            }
        });
        if (peaks.size() > MAX_PEAKS * 2) {
            return new ArrayList<>(peaks.subList(0, MAX_PEAKS * 2));
        }
        return peaks;
    }

    private static List<Peak> suppressNearbyPeaks(List<Peak> peaks) {
        List<Peak> kept = new ArrayList<>();
        for (Peak candidate : peaks) {
            boolean near = false;
            for (Peak existing : kept) {
                double dx = candidate.x - existing.x;
                double dy = candidate.y - existing.y;
                if (Math.hypot(dx, dy) < MIN_PEAK_DISTANCE) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                kept.add(candidate);
                if (kept.size() >= MAX_PEAKS) break;
            }
        }
        return kept;
    }

    private static List<Peak> selectSeparatedPeaks(
            List<Peak> peaks, float[] distance, byte[] boundary, int width, int height) {
        boolean[] selected = new boolean[peaks.size()];
        for (int i = 0; i < peaks.size(); i++) {
            Peak a = peaks.get(i);
            for (int j = i + 1; j < peaks.size(); j++) {
                Peak b = peaks.get(j);
                int saddleIndex = -1;
                float minimumDistance = Float.MAX_VALUE;
                int steps = Math.max(20, (int) (Math.hypot(b.x - a.x, b.y - a.y) * 2));
                for (int step = 0; step <= steps; step++) {
                    double t = step / (double) steps;
                    int x = clamp((int) Math.round(a.x + (b.x - a.x) * t), 0, width - 1);
                    int y = clamp((int) Math.round(a.y + (b.y - a.y) * t), 0, height - 1);
                    float value = distance[y * width + x];
                    if (value < minimumDistance) {
                        minimumDistance = value;
                        saddleIndex = y * width + x;
                    }
                }
                if (saddleIndex < 0) continue;

                float smallerRadius = Math.min(a.radius, b.radius);
                double saddleRatio = minimumDistance / Math.max(1.0, smallerRadius);
                int sx = saddleIndex % width;
                int sy = saddleIndex / width;
                int boundaryStrength = maxBoundaryNear(boundary, sx, sy, width, height, 3);

                boolean veryNarrowNeck = saddleRatio <= STRONG_SADDLE_RATIO;
                boolean boundarySupportedNeck = saddleRatio <= BOUNDARY_SADDLE_RATIO
                        && boundaryStrength >= BOUNDARY_EVIDENCE;
                if (veryNarrowNeck || boundarySupportedNeck) {
                    selected[i] = true;
                    selected[j] = true;
                }
            }
        }

        List<Peak> result = new ArrayList<>();
        for (int i = 0; i < peaks.size(); i++) {
            if (selected[i]) result.add(peaks.get(i));
        }
        return result;
    }

    private static int maxBoundaryNear(
            byte[] boundary, int cx, int cy, int width, int height, int radius) {
        int max = 0;
        for (int y = Math.max(0, cy - radius); y <= Math.min(height - 1, cy + radius); y++) {
            for (int x = Math.max(0, cx - radius); x <= Math.min(width - 1, cx + radius); x++) {
                max = Math.max(max, boundary[y * width + x] & 0xFF);
            }
        }
        return max;
    }

    private static Mat buildWatershedImage(Mat source, Mat boundaryMap, Rect rect) {
        Mat gray = new Mat();
        if (source.channels() == 1) {
            source.copyTo(gray);
        } else if (source.channels() == 4) {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGRA2GRAY);
        } else {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
        }
        Mat grayRoi = gray.submat(rect);
        Mat boundaryRoi = boundaryMap.submat(rect);
        Mat combined = new Mat();
        Mat bgr = new Mat();
        try {
            // Keep both the comic's ink outline and the model's predicted boundary
            // visible to watershed. The boundary head has a higher weight.
            Core.addWeighted(grayRoi, 0.55, boundaryRoi, 0.90, 0, combined);
            Imgproc.cvtColor(combined, bgr, Imgproc.COLOR_GRAY2BGR);
            return bgr;
        } catch (Throwable t) {
            bgr.release();
            throw t;
        } finally {
            gray.release();
            grayRoi.release();
            boundaryRoi.release();
            combined.release();
        }
    }

    private static Rect paddedRect(int x, int y, int w, int h, int padding, int imageW, int imageH) {
        int left = Math.max(0, x - padding);
        int top = Math.max(0, y - padding);
        int right = Math.min(imageW, x + w + padding);
        int bottom = Math.min(imageH, y + h + padding);
        return new Rect(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    private static int countForegroundComponents(Mat mask) {
        Mat labels = new Mat();
        try {
            return Imgproc.connectedComponents(mask, labels, 8, CvType.CV_32S);
        } finally {
            labels.release();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Peak {
        final int x;
        final int y;
        final float radius;

        Peak(int x, int y, float radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }
}
