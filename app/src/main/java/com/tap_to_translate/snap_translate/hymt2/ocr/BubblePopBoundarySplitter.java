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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Diagnostic instance separation driven by BubblePop's predicted boundary head.
 *
 * Unlike the previous watershed experiment, this does not use artwork intensity,
 * distance-transform peaks, or text strokes as watershed terrain. It removes only
 * high-confidence boundary pixels from the binary bubble mask and then labels the
 * remaining connected interiors. If the predicted boundary does not produce a
 * clean, substantial split, the original component is kept unchanged.
 */
public final class BubblePopBoundarySplitter {
    private BubblePopBoundarySplitter() {}

    /** Adjustable diagnostic-only parameters. Defaults preserve the original behavior. */
    public static final class Options {
        public final int boundaryThresholdStart;
        public final int boundaryThresholdStep;
        public final int boundaryThresholdAttempts;
        public final int minComponentArea;
        public final int minPartArea;
        public final double minPartAreaFraction;
        public final int maxParts;
        public final double minRetainedFraction;
        public final int closeKernelSize;
        public final double geometryMaxSaddleRatio;
        public final double geometryMinPeakRadius;
        public final double geometryMinPeakDistance;

        public Options(int boundaryThresholdStart, int boundaryThresholdStep,
                       int boundaryThresholdAttempts, int minComponentArea,
                       int minPartArea, double minPartAreaFraction, int maxParts,
                       double minRetainedFraction, int closeKernelSize) {
            this(boundaryThresholdStart, boundaryThresholdStep, boundaryThresholdAttempts,
                    minComponentArea, minPartArea, minPartAreaFraction, maxParts,
                    minRetainedFraction, closeKernelSize, 0.92, 5.0, 16.0);
        }

        public Options(int boundaryThresholdStart, int boundaryThresholdStep,
                       int boundaryThresholdAttempts, int minComponentArea,
                       int minPartArea, double minPartAreaFraction, int maxParts,
                       double minRetainedFraction, int closeKernelSize,
                       double geometryMaxSaddleRatio, double geometryMinPeakRadius,
                       double geometryMinPeakDistance) {
            this.boundaryThresholdStart = clamp(boundaryThresholdStart, 1, 255);
            this.boundaryThresholdStep = clamp(boundaryThresholdStep, 1, 64);
            this.boundaryThresholdAttempts = clamp(boundaryThresholdAttempts, 1, 12);
            this.minComponentArea = Math.max(1, minComponentArea);
            this.minPartArea = Math.max(1, minPartArea);
            this.minPartAreaFraction = Math.max(0.0, Math.min(0.5, minPartAreaFraction));
            this.maxParts = clamp(maxParts, 2, 12);
            this.minRetainedFraction = Math.max(0.1, Math.min(1.0, minRetainedFraction));
            int kernel = clamp(closeKernelSize, 1, 15);
            this.closeKernelSize = (kernel & 1) == 0 ? kernel + 1 : kernel;
            this.geometryMaxSaddleRatio = Math.max(0.45, Math.min(1.15, geometryMaxSaddleRatio));
            this.geometryMinPeakRadius = Math.max(2.0, Math.min(30.0, geometryMinPeakRadius));
            this.geometryMinPeakDistance = Math.max(6.0, Math.min(80.0, geometryMinPeakDistance));
        }

        public static Options defaults() {
            return new Options(176, 16, 5, 700, 120, 0.08, 4, 0.82, 3, 0.92, 5.0, 16.0);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    public static final class Result {
        public final Mat mask;
        /** CV_32SC1: a positive, unique ID for each retained bubble instance. */
        public final Mat instances;
        public final int instanceCount;
        public final int inputComponents;
        public final int outputComponents;
        public final int splitComponents;
        public final int boundaryThreshold;
        public final String geometrySummary;

        private Result(Mat mask, Mat instances, int instanceCount,
                       int inputComponents, int outputComponents,
                       int splitComponents, int boundaryThreshold, String geometrySummary) {
            this.mask = mask;
            this.instances = instances;
            this.instanceCount = instanceCount;
            this.inputComponents = inputComponents;
            this.outputComponents = outputComponents;
            this.splitComponents = splitComponents;
            this.boundaryThreshold = boundaryThreshold;
            this.geometrySummary = geometrySummary;
        }
    }

    /** Caller must release both returned Mats. */
    public static Result split(Mat inputMask, Mat boundaryMap) {
        return split(inputMask, boundaryMap, Options.defaults());
    }

    /** Caller must release both returned Mats. */
    public static Result split(Mat inputMask, Mat boundaryMap, Options options) {
        if (options == null) options = Options.defaults();
        if (inputMask == null || boundaryMap == null
                || inputMask.empty() || boundaryMap.empty()) {
            throw new IllegalArgumentException("mask and boundary map must be non-empty");
        }
        if (inputMask.rows() != boundaryMap.rows()
                || inputMask.cols() != boundaryMap.cols()) {
            throw new IllegalArgumentException("mask and boundary map dimensions differ");
        }

        Mat binary = new Mat();
        Mat boundary8 = new Mat();
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        Mat output = Mat.zeros(inputMask.size(), CvType.CV_8UC1);
        Mat instances = Mat.zeros(inputMask.size(), CvType.CV_32SC1);
        int inputCount = 0;
        int splitCount = 0;
        int nextId = 1;
        int usedThreshold = -1;
        GeometryStats geometryStats = new GeometryStats();

        try {
            if (inputMask.channels() == 1) {
                inputMask.convertTo(binary, CvType.CV_8UC1);
            } else {
                Imgproc.cvtColor(inputMask, binary, Imgproc.COLOR_BGR2GRAY);
                binary.convertTo(binary, CvType.CV_8UC1);
            }
            Imgproc.threshold(binary, binary, 0, 255, Imgproc.THRESH_BINARY);

            if (boundaryMap.depth() == CvType.CV_8U) {
                boundaryMap.copyTo(boundary8);
            } else {
                double scale = boundaryMap.depth() == CvType.CV_32F
                        || boundaryMap.depth() == CvType.CV_64F ? 255.0 : 1.0;
                boundaryMap.convertTo(boundary8, CvType.CV_8UC1, scale);
            }

            inputCount = Imgproc.connectedComponentsWithStats(
                    binary, labels, stats, centroids, 8, CvType.CV_32S);

            int kernelSize = options.closeKernelSize;
            Mat closeKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, new Size(kernelSize, kernelSize));
            try {
                for (int componentId = 1; componentId < inputCount; componentId++) {
                    int area = (int) stats.get(componentId, Imgproc.CC_STAT_AREA)[0];
                    int x = (int) stats.get(componentId, Imgproc.CC_STAT_LEFT)[0];
                    int y = (int) stats.get(componentId, Imgproc.CC_STAT_TOP)[0];
                    int w = (int) stats.get(componentId, Imgproc.CC_STAT_WIDTH)[0];
                    int h = (int) stats.get(componentId, Imgproc.CC_STAT_HEIGHT)[0];
                    Rect rect = new Rect(x, y, w, h);

                    Mat labelRoi = labels.submat(rect);
                    Mat component = new Mat();
                    Core.compare(labelRoi, new Scalar(componentId), component, Core.CMP_EQ);
                    labelRoi.release();

                    Candidate accepted = null;
                    if (area >= options.minComponentArea) {
                        Mat boundaryRoi = boundary8.submat(rect);
                        try {
                            for (int attempt = 0; attempt < options.boundaryThresholdAttempts; attempt++) {
                                int threshold = options.boundaryThresholdStart
                                        - attempt * options.boundaryThresholdStep;
                                if (threshold < 1) break;
                                Candidate candidate = tryBoundarySplit(
                                        component, boundaryRoi, threshold, closeKernel, options);
                                if (candidate != null) {
                                    accepted = candidate;
                                    usedThreshold = usedThreshold < 0
                                            ? threshold : Math.min(usedThreshold, threshold);
                                    break;
                                }
                            }

                            // The model boundary head is supporting evidence, not a hard
                            // prerequisite. If it has no continuous seam, look for a
                            // geometrically narrow neck between two substantial lobes.
                            if (accepted == null) {
                                accepted = tryGeometricSplit(component, boundaryRoi, options, geometryStats);
                            }
                        } finally {
                            boundaryRoi.release();
                        }
                    }

                    if (accepted == null) {
                        copyAsOneInstance(component, output.submat(rect),
                                instances.submat(rect), nextId++);
                    } else {
                        Mat outputRoi = output.submat(rect);
                        accepted.mask.copyTo(outputRoi);
                        outputRoi.release();
                        int[] localIds = new int[accepted.labels.rows() * accepted.labels.cols()];
                        accepted.labels.get(0, 0, localIds);
                        int[] remapped = new int[localIds.length];
                        for (int i = 0; i < localIds.length; i++) {
                            int localId = localIds[i];
                            if (localId > 0 && localId < accepted.idMap.length) {
                                remapped[i] = nextId + accepted.idMap[localId] - 1;
                            }
                        }
                        Mat localInstances = new Mat(
                                accepted.labels.rows(), accepted.labels.cols(), CvType.CV_32SC1);
                        localInstances.put(0, 0, remapped);
                        Mat instanceRoi = instances.submat(rect);
                        localInstances.copyTo(instanceRoi);
                        instanceRoi.release();
                        localInstances.release();
                        nextId += accepted.partCount;
                        splitCount++;
                        accepted.release();
                    }
                    component.release();
                }
            } finally {
                closeKernel.release();
            }

            int count = nextId - 1;
            return new Result(output, instances, count, inputCount - 1,
                    count, splitCount, usedThreshold, geometryStats.summary(options));
        } catch (Throwable error) {
            output.release();
            instances.release();
            throw error;
        } finally {
            binary.release();
            boundary8.release();
            labels.release();
            stats.release();
            centroids.release();
        }
    }

    private static Candidate tryBoundarySplit(
            Mat component, Mat boundaryRoi, int threshold, Mat closeKernel, Options options) {
        Mat barrier = new Mat();
        Mat insideBarrier = new Mat();
        Mat inverseBarrier = new Mat();
        Mat cut = new Mat();
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        try {
            Imgproc.threshold(boundaryRoi, barrier, threshold - 1, 255, Imgproc.THRESH_BINARY);
            Core.bitwise_and(barrier, component, insideBarrier);
            Imgproc.morphologyEx(insideBarrier, insideBarrier, Imgproc.MORPH_CLOSE, closeKernel);
            Core.bitwise_not(insideBarrier, inverseBarrier);
            Core.bitwise_and(component, inverseBarrier, cut);

            int count = Imgproc.connectedComponentsWithStats(
                    cut, labels, stats, centroids, 8, CvType.CV_32S);
            if (count < 3 || count - 1 > options.maxParts) return null;

            int minArea = Math.max(options.minPartArea,
                    (int) Math.round(Core.countNonZero(component) * options.minPartAreaFraction));
            int[] idMap = new int[count];
            int accepted = 0;
            int retainedArea = 0;
            for (int id = 1; id < count; id++) {
                int area = (int) stats.get(id, Imgproc.CC_STAT_AREA)[0];
                if (area >= minArea) {
                    idMap[id] = ++accepted;
                    retainedArea += area;
                }
            }
            int originalArea = Core.countNonZero(component);
            if (accepted < 2 || accepted > options.maxParts
                    || retainedArea < originalArea * options.minRetainedFraction) {
                return null;
            }

            Mat retained = Mat.zeros(component.size(), CvType.CV_8UC1);
            for (int id = 1; id < count; id++) {
                if (idMap[id] == 0) continue;
                Mat part = new Mat();
                Core.compare(labels, new Scalar(id), part, Core.CMP_EQ);
                Core.bitwise_or(retained, part, retained);
                part.release();
            }
            Candidate result = new Candidate(retained, labels.clone(), idMap, accepted);
            return result;
        } finally {
            barrier.release();
            insideBarrier.release();
            inverseBarrier.release();
            cut.release();
            labels.release();
            stats.release();
            centroids.release();
        }
    }

    /**
     * Detect and split one narrow geometric neck even when BubblePop did not
     * predict a usable internal boundary. The distance-transform saddle is
     * measured along the best path through the mask, so curved and offset
     * bridges are handled without drawing a straight chord between lobe centers.
     *
     * Only two lobe markers are used. This deliberately avoids the small
     * multi-seed fragments produced by unconstrained watershed experiments.
     */
    private static Candidate tryGeometricSplit(
            Mat component, Mat boundaryRoi, Options options, GeometryStats stats) {
        final int width = component.cols();
        final int height = component.rows();
        final int total = width * height;
        final int originalArea = Core.countNonZero(component);
        if (originalArea < options.minComponentArea || width < 3 || height < 3) {
            stats.rejectedTooSmall++;
            return null;
        }
        stats.attempts++;

        Mat distance = new Mat();
        Mat dilated = new Mat();
        Mat peakMask = new Mat();
        Mat radiusMask = new Mat();
        Mat peakLabels = new Mat();
        Mat peakStats = new Mat();
        Mat peakCentroids = new Mat();
        Mat markers = new Mat();
        Mat background = new Mat();
        Mat inverseDistance = new Mat();
        Mat boundary8 = new Mat();
        Mat terrain = new Mat();
        Mat watershedImage = new Mat();
        try {
            Imgproc.distanceTransform(component, distance, Imgproc.DIST_L2, 5);
            Mat peakKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, new Size(15, 15));
            try {
                Imgproc.dilate(distance, dilated, peakKernel);
            } finally {
                peakKernel.release();
            }
            Core.compare(distance, dilated, peakMask, Core.CMP_GE);
            Core.compare(distance, new Scalar(options.geometryMinPeakRadius), radiusMask, Core.CMP_GE);
            Core.bitwise_and(peakMask, radiusMask, peakMask);

            int peakCount = Imgproc.connectedComponentsWithStats(
                    peakMask, peakLabels, peakStats, peakCentroids, 8, CvType.CV_32S);
            if (peakCount <= 2) {
                stats.rejectedNoPeaks++;
                return null;
            }

            float[] distanceData = new float[total];
            int[] peakIds = new int[total];
            distance.get(0, 0, distanceData);
            peakLabels.get(0, 0, peakIds);

            float[] maxRadius = new float[peakCount];
            int[] maxIndex = new int[peakCount];
            Arrays.fill(maxIndex, -1);
            for (int i = 0; i < total; i++) {
                int id = peakIds[i];
                if (id > 0 && id < peakCount && distanceData[i] > maxRadius[id]) {
                    maxRadius[id] = distanceData[i];
                    maxIndex[id] = i;
                }
            }

            List<Peak> peaks = new ArrayList<>();
            for (int id = 1; id < peakCount; id++) {
                int index = maxIndex[id];
                if (index >= 0 && maxRadius[id] >= options.geometryMinPeakRadius) {
                    peaks.add(new Peak(index % width, index / width, maxRadius[id]));
                }
            }
            Collections.sort(peaks, new Comparator<Peak>() {
                @Override public int compare(Peak a, Peak b) {
                    return Float.compare(b.radius, a.radius);
                }
            });

            // Non-maximum suppression: one representative for each broad lobe.
            List<Peak> separated = new ArrayList<>();
            for (Peak peak : peaks) {
                boolean near = false;
                for (Peak kept : separated) {
                    if (Math.hypot(peak.x - kept.x, peak.y - kept.y)
                            < options.geometryMinPeakDistance) {
                        near = true;
                        break;
                    }
                }
                if (!near) separated.add(peak);
                if (separated.size() >= 8) break;
            }
            if (separated.size() < 2) {
                stats.rejectedNoPeaks++;
                return null;
            }

            byte[] componentData = new byte[total];
            byte[] boundaryData = new byte[total];
            component.get(0, 0, componentData);
            if (boundaryRoi.depth() == CvType.CV_8U) {
                boundaryRoi.get(0, 0, boundaryData);
            } else {
                Mat scaled = new Mat();
                try {
                    double scale = boundaryRoi.depth() == CvType.CV_32F
                            || boundaryRoi.depth() == CvType.CV_64F ? 255.0 : 1.0;
                    boundaryRoi.convertTo(scaled, CvType.CV_8UC1, scale);
                    scaled.get(0, 0, boundaryData);
                } finally {
                    scaled.release();
                }
            }

            Peak first = null;
            Peak second = null;
            double bestRatio = Double.POSITIVE_INFINITY;
            int bestBoundary = 0;
            for (int i = 0; i < separated.size(); i++) {
                Peak a = separated.get(i);
                SaddleMap saddleMap = widestPathSaddles(a, distanceData,
                        componentData, width, height);
                try {
                    for (int j = i + 1; j < separated.size(); j++) {
                        Peak b = separated.get(j);
                        int target = b.y * width + b.x;
                        float saddle = saddleMap.capacity[target];
                        if (saddle < 0.0f) continue;
                        double ratio = saddle / Math.max(1.0,
                                Math.min(a.radius, b.radius));
                        if (ratio >= bestRatio) continue;
                        int saddleIndex = saddleMap.bottleneck[target];
                        int sx = saddleIndex >= 0 ? saddleIndex % width : (a.x + b.x) / 2;
                        int sy = saddleIndex >= 0 ? saddleIndex / width : (a.y + b.y) / 2;
                        int boundaryStrength = maxBoundaryNear(
                                boundaryData, sx, sy, width, height, 5);
                        bestRatio = ratio;
                        bestBoundary = boundaryStrength;
                        first = a;
                        second = b;
                    }
                } finally {
                    saddleMap.release();
                }
            }
            if (first == null || second == null) {
                stats.rejectedNoPath++;
                return null;
            }
            stats.recordSaddle(bestRatio, bestBoundary);

            // The first-button controls now tune this decision. Higher saddle
            // ratios allow wider bridges; lower values are more conservative.
            boolean strongGeometricNeck = bestRatio <= options.geometryMaxSaddleRatio;
            boolean boundarySupportedNeck = bestRatio <= Math.min(1.15,
                    options.geometryMaxSaddleRatio + 0.08)
                    && bestBoundary >= options.boundaryThresholdStart;
            if (!strongGeometricNeck && !boundarySupportedNeck) {
                stats.rejectedNeckTooWide++;
                return null;
            }

            markers.create(height, width, CvType.CV_32SC1);
            markers.setTo(new Scalar(0));
            Core.bitwise_not(component, background);
            markers.setTo(new Scalar(1), background);

            int firstRadius = Math.max(2, Math.min(4, Math.round(first.radius * 0.18f)));
            int secondRadius = Math.max(2, Math.min(4, Math.round(second.radius * 0.18f)));
            Imgproc.circle(markers, new Point(first.x, first.y), firstRadius,
                    new Scalar(2), -1, Imgproc.LINE_8, 0);
            Imgproc.circle(markers, new Point(second.x, second.y), secondRadius,
                    new Scalar(3), -1, Imgproc.LINE_8, 0);

            Core.normalize(distance, inverseDistance, 255.0, 0.0,
                    Core.NORM_MINMAX, CvType.CV_8UC1);
            if (boundaryRoi.depth() == CvType.CV_8U) {
                boundaryRoi.copyTo(boundary8);
            } else {
                double scale = boundaryRoi.depth() == CvType.CV_32F
                        || boundaryRoi.depth() == CvType.CV_64F ? 255.0 : 1.0;
                boundaryRoi.convertTo(boundary8, CvType.CV_8UC1, scale);
            }
            // Use mask geometry as the terrain. Boundary predictions reinforce
            // an existing neck but artwork brightness and text strokes are excluded.
            Core.addWeighted(inverseDistance, 0.72, boundary8, 0.90, 0.0, terrain);
            Imgproc.cvtColor(terrain, watershedImage, Imgproc.COLOR_GRAY2BGR);
            Imgproc.watershed(watershedImage, markers);

            int[] markerData = new int[total];
            markers.get(0, 0, markerData);
            int firstArea = 0;
            int secondArea = 0;
            int retainedArea = 0;
            for (int i = 0; i < total; i++) {
                if ((componentData[i] & 0xFF) == 0) continue;
                if (markerData[i] == 2) firstArea++;
                else if (markerData[i] == 3) secondArea++;
            }

            int minimumArea = Math.max(options.minPartArea,
                    (int) Math.ceil(originalArea * options.minPartAreaFraction));
            if (firstArea < minimumArea || secondArea < minimumArea) {
                stats.rejectedPartTooSmall++;
                return null;
            }

            byte[] splitMask = new byte[total];
            int[] splitLabels = new int[total];
            for (int i = 0; i < total; i++) {
                if ((componentData[i] & 0xFF) == 0) continue;
                if (markerData[i] == 2 || markerData[i] == 3) {
                    splitMask[i] = (byte) 255;
                    splitLabels[i] = markerData[i] - 1;
                    retainedArea++;
                }
            }
            if (retainedArea < originalArea * options.minRetainedFraction) {
                stats.rejectedRetainedArea++;
                return null;
            }
            stats.accepted++;

            Mat retained = new Mat(height, width, CvType.CV_8UC1);
            retained.put(0, 0, splitMask);
            Mat labels = new Mat(height, width, CvType.CV_32SC1);
            labels.put(0, 0, splitLabels);
            int[] idMap = new int[] {0, 1, 2};
            return new Candidate(retained, labels, idMap, 2);
        } finally {
            distance.release();
            dilated.release();
            peakMask.release();
            radiusMask.release();
            peakLabels.release();
            peakStats.release();
            peakCentroids.release();
            markers.release();
            background.release();
            inverseDistance.release();
            boundary8.release();
            terrain.release();
            watershedImage.release();
        }
    }

    private static SaddleMap widestPathSaddles(Peak start, float[] distance,
                                                byte[] mask, int width, int height) {
        int total = width * height;
        int sourceIndex = start.y * width + start.x;
        float[] best = new float[total];
        int[] bottleneck = new int[total];
        Arrays.fill(best, -1.0f);
        Arrays.fill(bottleneck, -1);
        PriorityQueue<PathNode> queue = new PriorityQueue<>(new Comparator<PathNode>() {
            @Override public int compare(PathNode a, PathNode b) {
                return Float.compare(b.capacity, a.capacity);
            }
        });
        best[sourceIndex] = distance[sourceIndex];
        bottleneck[sourceIndex] = sourceIndex;
        queue.add(new PathNode(sourceIndex, best[sourceIndex]));

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            if (current.capacity < best[current.index]) continue;
            int x = current.index % width;
            int y = current.index / width;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    int next = ny * width + nx;
                    if ((mask[next] & 0xFF) == 0) continue;
                    float capacity = Math.min(current.capacity, distance[next]);
                    if (capacity > best[next]) {
                        best[next] = capacity;
                        bottleneck[next] = distance[next] < current.capacity
                                ? next : bottleneck[current.index];
                        queue.add(new PathNode(next, capacity));
                    }
                }
            }
        }
        return new SaddleMap(best, bottleneck);
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

    private static final class GeometryStats {
        int attempts;
        int rejectedTooSmall;
        int rejectedNoPeaks;
        int rejectedNoPath;
        int rejectedNeckTooWide;
        int rejectedPartTooSmall;
        int rejectedRetainedArea;
        int accepted;
        double bestSaddleRatio = Double.POSITIVE_INFINITY;
        int bestBoundary;

        void recordSaddle(double ratio, int boundary) {
            if (ratio < bestSaddleRatio) {
                bestSaddleRatio = ratio;
                bestBoundary = boundary;
            }
        }

        String summary(Options options) {
            String best = Double.isInfinite(bestSaddleRatio)
                    ? "нет" : (Math.round(bestSaddleRatio * 100.0) + "%");
            return "Геометрия: попыток " + attempts
                    + ", разделено " + accepted
                    + ", недостаточно центров " + rejectedNoPeaks
                    + ", нет пути " + rejectedNoPath
                    + ", перемычка шире порога " + rejectedNeckTooWide
                    + ", части малы " + rejectedPartTooSmall
                    + ", потеря площади " + rejectedRetainedArea
                    + ", лучшая перемычка " + best
                    + " / порог " + Math.round(options.geometryMaxSaddleRatio * 100.0) + "%";
        }
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

    private static final class PathNode {
        final int index;
        final float capacity;
        PathNode(int index, float capacity) {
            this.index = index;
            this.capacity = capacity;
        }
    }

    private static final class SaddleMap {
        final float[] capacity;
        final int[] bottleneck;
        SaddleMap(float[] capacity, int[] bottleneck) {
            this.capacity = capacity;
            this.bottleneck = bottleneck;
        }
        void release() {
            // Primitive arrays are reclaimed by the runtime after this reference is dropped.
        }
    }

    private static void copyAsOneInstance(Mat component, Mat outputRoi,
                                          Mat instancesRoi, int id) {
        component.copyTo(outputRoi);
        instancesRoi.setTo(new Scalar(id), component);
        outputRoi.release();
        instancesRoi.release();
    }

    private static final class Candidate {
        final Mat mask;
        final Mat labels;
        final int[] idMap;
        final int partCount;

        Candidate(Mat mask, Mat labels, int[] idMap, int partCount) {
            this.mask = mask;
            this.labels = labels;
            this.idMap = idMap;
            this.partCount = partCount;
        }

        void release() {
            mask.release();
            labels.release();
        }
    }
}
