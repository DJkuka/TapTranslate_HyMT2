package com.tap_to_translate.snap_translate.hymt2.ocr;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfInt4;
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
            return new Options(176, 16, 5, 700, 120, 0.08, 4, 0.82, 3, 0.92, 3.0, 9.0);
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
        public final int boundaryAttemptCount;
        public final int boundaryAcceptedCount;
        public final int boundaryMinThresholdTried;
        public final int boundaryMaxThresholdTried;
        /** Per-component geometry diagnostics in full input-image coordinates. */
        public final List<GeometryTrace> geometryTraces;
        public final List<ConcavityTrace> concavityTraces;

        private Result(Mat mask, Mat instances, int instanceCount,
                       int inputComponents, int outputComponents,
                       int splitComponents, int boundaryThreshold, String geometrySummary,
                       int boundaryAttemptCount, int boundaryAcceptedCount,
                       int boundaryMinThresholdTried, int boundaryMaxThresholdTried,
                       List<GeometryTrace> geometryTraces, List<ConcavityTrace> concavityTraces) {
            this.mask = mask;
            this.instances = instances;
            this.instanceCount = instanceCount;
            this.inputComponents = inputComponents;
            this.outputComponents = outputComponents;
            this.splitComponents = splitComponents;
            this.boundaryThreshold = boundaryThreshold;
            this.geometrySummary = geometrySummary;
            this.boundaryAttemptCount = boundaryAttemptCount;
            this.boundaryAcceptedCount = boundaryAcceptedCount;
            this.boundaryMinThresholdTried = boundaryMinThresholdTried;
            this.boundaryMaxThresholdTried = boundaryMaxThresholdTried;
            this.geometryTraces = Collections.unmodifiableList(new ArrayList<>(geometryTraces));
            this.concavityTraces = Collections.unmodifiableList(new ArrayList<>(concavityTraces));
        }
    }

    /** Caller must release both returned Mats. */
    public static Result split(Mat inputMask, Mat boundaryMap) {
        return split(null, inputMask, boundaryMap, Options.defaults());
    }

    /** Backwards-compatible overload; text evidence is neutral when source pixels are unavailable. */
    public static Result split(Mat inputMask, Mat boundaryMap, Options options) {
        return split(null, inputMask, boundaryMap, options);
    }

    /**
     * Unified four-signal splitter:
     * 1) convexity-defect concavities propose cuts;
     * 2) distance-transform peaks and the widest-path saddle propose/check cuts;
     * 3) dark text-like glyph components are checked on both sides;
     * 4) each resulting partition is scored for balance, retained area and topology.
     * The source image is used only for text evidence; no artwork-intensity watershed is used.
     */
    public static Result split(Mat source, Mat inputMask, Mat boundaryMap, Options options) {
        if (options == null) options = Options.defaults();
        if (inputMask == null || boundaryMap == null
                || inputMask.empty() || boundaryMap.empty()) {
            throw new IllegalArgumentException("mask and boundary map must be non-empty");
        }
        if (inputMask.rows() != boundaryMap.rows()
                || inputMask.cols() != boundaryMap.cols()) {
            throw new IllegalArgumentException("mask and boundary map dimensions differ");
        }
        if (source != null && (source.empty()
                || source.rows() != inputMask.rows() || source.cols() != inputMask.cols())) {
            throw new IllegalArgumentException("source and mask dimensions differ");
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
        int boundaryAttemptCount = 0;
        int boundaryAcceptedCount = 0;
        int boundaryMinThresholdTried = Integer.MAX_VALUE;
        int boundaryMaxThresholdTried = -1;
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

                    Candidate boundaryCandidate = null;
                    Candidate concavityCandidate = null;
                    Candidate geometricCandidate = null;
                    Candidate accepted = null;
                    if (area >= options.minComponentArea) {
                        Mat boundaryRoi = boundary8.submat(rect);
                        try {
                            // Boundary head is a real third proposal source. Test a
                            // descending threshold range and keep the strongest valid cut.
                            for (int attempt = 0; attempt < options.boundaryThresholdAttempts; attempt++) {
                                int threshold = Math.max(1,
                                        options.boundaryThresholdStart - attempt * options.boundaryThresholdStep);
                                boundaryAttemptCount++;
                                boundaryMinThresholdTried = Math.min(boundaryMinThresholdTried, threshold);
                                boundaryMaxThresholdTried = Math.max(boundaryMaxThresholdTried, threshold);
                                Candidate proposal = tryBoundarySplit(
                                        component, boundaryRoi, threshold, closeKernel, options);
                                if (proposal != null) {
                                    boundaryAcceptedCount++;
                                    if (boundaryCandidate == null
                                            || proposal.proposalScore > boundaryCandidate.proposalScore) {
                                        if (boundaryCandidate != null) boundaryCandidate.release();
                                        boundaryCandidate = proposal;
                                        usedThreshold = threshold;
                                    } else {
                                        proposal.release();
                                    }
                                }
                            }
                            concavityCandidate = tryConcavitySplit(component, geometryStats, componentId, x, y,
                                    options.minPartArea, options.minPartAreaFraction,
                                    options.minRetainedFraction);
                            geometricCandidate = tryGeometricSplit(component, boundaryRoi,
                                    options, geometryStats, componentId, x, y);
                        } finally {
                            boundaryRoi.release();
                        }
                        Mat sourceRoi = source == null ? null : source.submat(rect);
                        try {
                            if (concavityCandidate != null && options.maxParts > 2) {
                                concavityCandidate = expandConcavityCandidate(
                                        component, sourceRoi, concavityCandidate,
                                        geometryStats, x, y, options, componentId,
                                        new int[] {options.maxParts - 2});
                            }
                            accepted = chooseCombinedCandidate(component, sourceRoi,
                                    boundaryCandidate, concavityCandidate, geometricCandidate,
                                    geometryStats, componentId);
                        } finally {
                            if (sourceRoi != null) sourceRoi.release();
                        }
                    }

                    if (boundaryCandidate != null && boundaryCandidate != accepted) {
                        boundaryCandidate.release();
                    }
                    if (concavityCandidate != null && concavityCandidate != accepted) {
                        concavityCandidate.release();
                    }
                    if (geometricCandidate != null && geometricCandidate != accepted) {
                        geometricCandidate.release();
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
                    count, splitCount, usedThreshold, geometryStats.summary(options),
                    boundaryAttemptCount, boundaryAcceptedCount,
                    boundaryAttemptCount == 0 ? -1 : boundaryMinThresholdTried,
                    boundaryMaxThresholdTried, geometryStats.traces, geometryStats.concavityTraces);
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
            double boundaryEvidence = Core.mean(boundaryRoi, insideBarrier).val[0] / 255.0;
            Candidate result = new Candidate(retained, labels.clone(), idMap, accepted,
                    "boundary", clamp01(boundaryEvidence));
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
            Mat component, Mat boundaryRoi, Options options, GeometryStats stats,
            int componentId, int offsetX, int offsetY) {
        final int width = component.cols();
        final int height = component.rows();
        final int total = width * height;
        final int originalArea = Core.countNonZero(component);
        GeometryTrace trace = new GeometryTrace(componentId, offsetX, offsetY, width, height, originalArea);
        stats.traces.add(trace);
        if (originalArea < options.minComponentArea || width < 3 || height < 3) {
            stats.rejectedTooSmall++;
            trace.outcome = "component_too_small";
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
            int peakKernelSize = Math.max(5, Math.min(11,
                    (Math.min(width, height) / 5) | 1));
            if ((peakKernelSize & 1) == 0) peakKernelSize--;
            Mat peakKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, new Size(peakKernelSize, peakKernelSize));
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
                trace.rawPeakComponents = Math.max(0, peakCount - 1);
                trace.outcome = "no_peak_candidates";
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
            trace.rawPeakComponents = peaks.size();
            trace.separatedPeaks = separated.size();
            for (Peak peak : separated) {
                trace.peaks.add(new PeakInfo(offsetX + peak.x, offsetY + peak.y, peak.radius));
            }
            if (separated.size() < 2) {
                stats.rejectedNoPeaks++;
                trace.outcome = "fewer_than_two_separated_centers";
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

            List<Peak> seedPeaks = separated.size() > options.maxParts
                    ? new ArrayList<>(separated.subList(0, options.maxParts))
                    : new ArrayList<>(separated);
            Peak first = null;
            Peak second = null;
            double bestRatio = Double.POSITIVE_INFINITY;
            int bestBoundary = 0;
            int bestSaddleIndex = -1;
            for (int i = 0; i < seedPeaks.size(); i++) {
                Peak a = seedPeaks.get(i);
                SaddleMap saddleMap = widestPathSaddles(a, distanceData,
                        componentData, width, height);
                try {
                    for (int j = i + 1; j < seedPeaks.size(); j++) {
                        Peak b = seedPeaks.get(j);
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
                        bestSaddleIndex = saddleIndex;
                        first = a;
                        second = b;
                    }
                } finally {
                    saddleMap.release();
                }
            }
            if (first == null || second == null) {
                stats.rejectedNoPath++;
                trace.outcome = "no_path_between_centers";
                return null;
            }
            trace.firstX = offsetX + first.x;
            trace.firstY = offsetY + first.y;
            trace.secondX = offsetX + second.x;
            trace.secondY = offsetY + second.y;
            trace.saddleRatio = bestRatio;
            trace.boundaryStrength = bestBoundary;
            // Reuse the bottleneck selected during pair ranking; no second path search.
            if (bestSaddleIndex >= 0) {
                trace.saddleX = offsetX + bestSaddleIndex % width;
                trace.saddleY = offsetY + bestSaddleIndex / width;
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
                trace.outcome = "neck_too_wide";
                return null;
            }
            trace.outcome = "watershed_running";

            markers.create(height, width, CvType.CV_32SC1);
            markers.setTo(new Scalar(0));
            Core.bitwise_not(component, background);
            markers.setTo(new Scalar(1), background);

            for (int seed = 0; seed < seedPeaks.size(); seed++) {
                Peak peak = seedPeaks.get(seed);
                int radius = Math.max(2, Math.min(4, Math.round(peak.radius * 0.18f)));
                Imgproc.circle(markers, new Point(peak.x, peak.y), radius,
                        new Scalar(seed + 2), -1, Imgproc.LINE_8, 0);
            }

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
            int partCount = seedPeaks.size();
            int[] partAreas = new int[partCount + 1];
            int retainedArea = 0;
            for (int i = 0; i < total; i++) {
                if ((componentData[i] & 0xFF) == 0) continue;
                // Watershed can leave foreground pixels as label 1 or -1.
                // Assign unclaimed foreground to the nearest seed, preserving
                // every original mask pixel while supporting 2..maxParts lobes.
                int marker = markerData[i];
                // OpenCV marks the actual watershed separator as -1.
                // Keep those pixels unassigned: they form the real neck-following cut.
                if (marker == -1) continue;
                if (marker < 2 || marker >= partCount + 2) {
                    int px = i % width;
                    int py = i / width;
                    int nearest = 0;
                    double nearestDistance = Double.POSITIVE_INFINITY;
                    for (int seed = 0; seed < partCount; seed++) {
                        Peak peak = seedPeaks.get(seed);
                        double dx = px - peak.x, dy = py - peak.y;
                        double distanceToSeed = dx * dx + dy * dy;
                        if (distanceToSeed < nearestDistance) {
                            nearestDistance = distanceToSeed;
                            nearest = seed;
                        }
                    }
                    marker = nearest + 2;
                    markerData[i] = marker;
                }
                int partId = marker - 1;
                if (partId >= 1 && partId <= partCount) {
                    partAreas[partId]++;
                    retainedArea++;
                }
            }

            int minimumArea = Math.max(options.minPartArea,
                    (int) Math.ceil(originalArea * options.minPartAreaFraction));
            int smallestPart = Integer.MAX_VALUE;
            for (int part = 1; part <= partCount; part++) {
                smallestPart = Math.min(smallestPart, partAreas[part]);
            }
            trace.firstArea = partAreas[1];
            trace.secondArea = partCount >= 2 ? partAreas[2] : 0;
            trace.minimumPartArea = minimumArea;
            if (smallestPart < minimumArea) {
                stats.rejectedPartTooSmall++;
                trace.outcome = "part_too_small";
                return null;
            }

            byte[] splitMask = new byte[total];
            int[] splitLabels = new int[total];
            for (int i = 0; i < total; i++) {
                if ((componentData[i] & 0xFF) == 0) continue;
                // Preserve the complete balloon mask, but leave watershed-ridge
                // pixels with instance ID 0 so adjacent instances remain separated.
                splitMask[i] = (byte) 255;
                int partId = markerData[i] - 1;
                if (partId >= 1 && partId <= partCount && markerData[i] != -1) {
                    splitLabels[i] = partId;
                }
            }
            trace.retainedArea = retainedArea;
            trace.retainedFraction = originalArea == 0 ? 0.0 : retainedArea / (double) originalArea;
            if (retainedArea < originalArea * options.minRetainedFraction) {
                stats.rejectedRetainedArea++;
                trace.outcome = "retained_area_too_low";
                return null;
            }
            stats.accepted++;
            trace.outcome = "accepted";

            Mat retained = new Mat(height, width, CvType.CV_8UC1);
            retained.put(0, 0, splitMask);
            Mat labels = new Mat(height, width, CvType.CV_32SC1);
            labels.put(0, 0, splitLabels);
            int[] idMap = new int[partCount + 1];
            for (int part = 1; part <= partCount; part++) idMap[part] = part;
            double geometricEvidence = clamp01(1.0 - bestRatio
                    / Math.max(0.45, options.geometryMaxSaddleRatio));
            return new Candidate(retained, labels, idMap, partCount,
                    "distance/saddle", geometricEvidence);
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

    /**
     * Experimental concavity-based split. It pairs deep convexity defects on
     * opposite sides of a merged silhouette, cuts between their far points,
     * and accepts only a cut that creates two substantial connected interiors.
     * This is diagnostic-only and does not affect the live OCR path.
     */
    private static Candidate tryConcavitySplit(Mat component, GeometryStats stats,
            int componentId, int offsetX, int offsetY, int configuredMinPartArea,
            double minPartAreaFraction, double minRetainedFraction) {
        stats.concavityAttempts++;
        ConcavityTrace trace = new ConcavityTrace(componentId);
        stats.concavityTraces.add(trace);
        Mat contourInput = component.clone();
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        MatOfInt hull = new MatOfInt();
        MatOfInt4 defects = new MatOfInt4();
        Mat barrier = Mat.zeros(component.size(), CvType.CV_8UC1);
        Mat cut = new Mat();
        Mat labels = new Mat();
        Mat ccStats = new Mat();
        Mat centroids = new Mat();
        try {
            Imgproc.findContours(contourInput, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE);
            if (contours.isEmpty()) {
                stats.concavityRejectedNoDefects++;
                return null;
            }
            MatOfPoint contour = Collections.max(contours, new Comparator<MatOfPoint>() {
                @Override public int compare(MatOfPoint a, MatOfPoint b) {
                    return Double.compare(Imgproc.contourArea(a), Imgproc.contourArea(b));
                }
            });
            if (contour.rows() < 5) {
                stats.concavityRejectedNoDefects++;
                return null;
            }
            // OpenCV convexityDefects requires hull vertex indices in strictly
            // increasing contour order. convexHull(returnPoints=false) returns
            // the hull in cyclic order, which is not necessarily monotonic.
            // Passing that cyclic order can trigger a native OpenCV error and,
            // on some Android builds, a SIGSEGV instead of a safe Java exception.
            Imgproc.convexHull(contour, hull, false);
            int[] hullIndices = hull.toArray();
            if (hullIndices.length < 3) {
                stats.concavityRejectedNoDefects++;
                trace.outcome = "convex_hull_too_small";
                return null;
            }
            Arrays.sort(hullIndices);
            boolean validHull = true;
            for (int k = 0; k < hullIndices.length; k++) {
                if (hullIndices[k] < 0 || hullIndices[k] >= contour.rows()
                        || (k > 0 && hullIndices[k] == hullIndices[k - 1])) {
                    validHull = false;
                    break;
                }
            }
            if (!validHull) {
                stats.concavityRejectedNoDefects++;
                trace.outcome = "convex_hull_indices_invalid";
                return null;
            }
            hull.fromArray(hullIndices);
            try {
                Imgproc.convexityDefects(contour, hull, defects);
            } catch (RuntimeException invalidHull) {
                stats.concavityRejectedNoDefects++;
                trace.outcome = "convexity_defects_unavailable";
                return null;
            }
            Point[] contourPoints = contour.toArray();
            int[] d = defects.toArray();
            if (d.length < 8) {
                stats.concavityRejectedNoDefects++;
                return null;
            }

            final int width = component.cols();
            final int height = component.rows();
            final int originalArea = Core.countNonZero(component);
            final int minArea = Math.max(configuredMinPartArea,
                    (int) Math.ceil(originalArea * minPartAreaFraction));
            byte[] maskData = new byte[width * height];
            component.get(0, 0, maskData);
            Mat distanceMap = new Mat();
            Imgproc.distanceTransform(component, distanceMap, Imgproc.DIST_L2, 5);
            float[] distanceValues = new float[width * height];
            distanceMap.get(0, 0, distanceValues);
            distanceMap.release();
            int bestA = -1, bestB = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            int bestRetained = 0, bestAreaA = 0, bestAreaB = 0;
            double bestTriangleArea = 0.0;
            double bestProposalScore = 0.0;
            int[] bestLabels = null;
            int[] bestIdMap = null;
            int defectCount = d.length / 4;
            trace.defectCount = defectCount;
            double maxSpan = Math.hypot(width, height) * 0.58;
            for (int i = 0; i < defectCount; i++) {
                int farI = d[i * 4 + 2];
                double depthI = d[i * 4 + 3] / 256.0;
                if (depthI < 4.0) continue;
                trace.deepDefects++;
                if (farI < 0 || farI >= contourPoints.length) continue;
                Point pi = contourPoints[farI];
                Point startI = contourPoints[d[i * 4]];
                Point endI = contourPoints[d[i * 4 + 1]];
                double triangleAreaI = triangleArea(startI, endI, pi);
                // The midpoint of the convex-hull chord gives the outward
                // direction of this indentation. A valid neck is normally
                // bounded by two valleys whose outward normals face away
                // from one another (their inward directions face each other).
                double normalIx = (startI.x + endI.x) * 0.5 - pi.x;
                double normalIy = (startI.y + endI.y) * 0.5 - pi.y;
                double normalILength = Math.hypot(normalIx, normalIy);
                if (normalILength < 1.0) continue;
                for (int j = i + 1; j < defectCount; j++) {
                    int farJ = d[j * 4 + 2];
                    double depthJ = d[j * 4 + 3] / 256.0;
                    if (depthJ < 4.0 || farI == farJ) continue;
                    if (farJ < 0 || farJ >= contourPoints.length) continue;
                    Point pj = contourPoints[farJ];
                    Point startJ = contourPoints[d[j * 4]];
                    Point endJ = contourPoints[d[j * 4 + 1]];
                    double triangleAreaJ = triangleArea(startJ, endJ, pj);
                    double pairedTriangleArea = Math.min(triangleAreaI, triangleAreaJ);
                    double normalJx = (startJ.x + endJ.x) * 0.5 - pj.x;
                    double normalJy = (startJ.y + endJ.y) * 0.5 - pj.y;
                    double normalJLength = Math.hypot(normalJx, normalJy);
                    if (normalJLength < 1.0) continue;
                    double facing = -((normalIx * normalJx + normalIy * normalJy)
                            / (normalILength * normalJLength));
                    // Reject arbitrary valleys on the same side of a balloon.
                    // Their hull-edge normals do not face each other and joining
                    // them tends to produce the recurring diagonal false cut.
                    if (facing < 0.35) continue;
                    trace.facingPairs++;

                    double span = Math.hypot(pi.x - pj.x, pi.y - pj.y);
                    if (span < 8.0 || span > maxSpan) continue;

                    // Trace a minimum-cost path through the narrowest foreground
                    // corridor between the two concavity valleys. A straight chord
                    // cuts diagonally across lobes; this path follows the neck.
                    List<Integer> neckPath = shortestNeckPath(maskData, distanceValues,
                            width, height, (int) Math.round(pi.x), (int) Math.round(pi.y),
                            (int) Math.round(pj.x), (int) Math.round(pj.y));
                    if (neckPath == null || neckPath.size() < 2) continue;
                    trace.neckPaths++;

                    barrier.setTo(new Scalar(0));
                    float narrowestRadius = Float.POSITIVE_INFINITY;
                    for (int pixel : neckPath) {
                        narrowestRadius = Math.min(narrowestRadius, distanceValues[pixel]);
                    }
                    int cutThickness = Math.max(3,
                            Math.min(9, 2 * (int) Math.ceil(narrowestRadius) + 1));
                    int previous = neckPath.get(0);
                    for (int pathIndex = 1; pathIndex < neckPath.size(); pathIndex++) {
                        int current = neckPath.get(pathIndex);
                        Imgproc.line(barrier,
                                new Point(previous % width, previous / width),
                                new Point(current % width, current / width),
                                new Scalar(255), cutThickness, Imgproc.LINE_8, 0);
                        previous = current;
                    }
                    Core.bitwise_not(barrier, cut);
                    Core.bitwise_and(component, cut, cut);
                    int count = Imgproc.connectedComponentsWithStats(
                            cut, labels, ccStats, centroids, 8, CvType.CV_32S);
                    if (count < 3) continue;
                    trace.disconnectedCuts++;

                    int firstId = -1, secondId = -1;
                    int firstSize = 0, secondSize = 0;
                    for (int id = 1; id < count; id++) {
                        int area = (int) ccStats.get(id, Imgproc.CC_STAT_AREA)[0];
                        if (area > firstSize) {
                            secondSize = firstSize; secondId = firstId;
                            firstSize = area; firstId = id;
                        } else if (area > secondSize) {
                            secondSize = area; secondId = id;
                        }
                    }
                    int retained = firstSize + secondSize;
                    if (firstSize < minArea || secondSize < minArea
                            || retained < originalArea * minRetainedFraction) continue;

                    // Prefer balanced large parts, then deeper and shorter concavity chords.
                    double balance = Math.min(firstSize, secondSize)
                            / (double) Math.max(firstSize, secondSize);
                    double retainedFraction = retained / (double) originalArea;
                    double depthScore = Math.min(depthI, depthJ);
                    double triangleScore = Math.min(1.0,
                            pairedTriangleArea / Math.max(12.0, originalArea * 0.015));
                    // Reject shallow slivers whose convexity-defect triangles carry
                    // almost no area, even if the chord happens to disconnect the mask.
                    if (triangleScore < 0.015) continue;
                    // Pair-facing is a required property, not a minor bonus.
                    // Among valid opposed valleys, prefer the shorter cut and
                    // retain the balance/depth/area evidence.
                    double score = balance * 1.8 + retainedFraction
                            + Math.min(12.0, depthScore) * 0.025
                            + triangleScore * 0.18 + facing * 0.55
                            - span / maxSpan * 0.22;
                    if (score <= bestScore) continue;
                    int[] localLabels = new int[width * height];
                    labels.get(0, 0, localLabels);
                    int[] idMap = new int[count];
                    idMap[firstId] = 1;
                    idMap[secondId] = 2;
                    int[] remappedLabels = new int[localLabels.length];
                    byte[] retainedMask = new byte[localLabels.length];
                    for (int q = 0; q < localLabels.length; q++) {
                        int id = localLabels[q];
                        if (id == firstId || id == secondId) {
                            remappedLabels[q] = idMap[id];
                            retainedMask[q] = (byte) 255;
                        }
                    }
                    bestScore = score;
                    trace.x1 = offsetX + (int) Math.round(pi.x);
                    trace.y1 = offsetY + (int) Math.round(pi.y);
                    trace.x2 = offsetX + (int) Math.round(pj.x);
                    trace.y2 = offsetY + (int) Math.round(pj.y);
                    trace.firstArea = firstSize;
                    trace.secondArea = secondSize;
                    trace.retainedArea = retained;
                    trace.outcome = "candidate";
                    bestA = (int) Math.round(pi.x);
                    bestB = (int) Math.round(pj.x);
                    bestRetained = retained;
                    bestAreaA = firstSize;
                    bestAreaB = secondSize;
                    bestTriangleArea = pairedTriangleArea;
                    trace.triangleArea = pairedTriangleArea;
                    bestProposalScore = clamp01(0.26 * balance
                            + 0.20 * retainedFraction
                            + 0.20 * clamp01(depthScore / 12.0)
                            + 0.14 * triangleScore
                            + 0.20 * facing);
                    bestLabels = remappedLabels;
                    bestIdMap = new int[] {0, 1, 2};
                    stats.concavityBestDepth = Math.max(stats.concavityBestDepth,
                            Math.min(depthI, depthJ));
                    stats.concavityBestRetained = retained;
                    stats.concavityBestAreaA = firstSize;
                    stats.concavityBestAreaB = secondSize;
                    stats.concavityFirstX = offsetX + (int) Math.round(pi.x);
                    stats.concavityFirstY = offsetY + (int) Math.round(pi.y);
                    stats.concavitySecondX = offsetX + (int) Math.round(pj.x);
                    stats.concavitySecondY = offsetY + (int) Math.round(pj.y);
                    stats.concavityLabels = bestLabels;
                    stats.concavityMask = retainedMask;
                }
            }
            if (bestLabels == null) {
                stats.concavityRejectedGeometry++;
                trace.outcome = "no_valid_chord";
                return null;
            }
            Mat retainedMask = new Mat(height, width, CvType.CV_8UC1);
            retainedMask.put(0, 0, stats.concavityMask);
            Mat retainedLabels = new Mat(height, width, CvType.CV_32SC1);
            retainedLabels.put(0, 0, bestLabels);
            stats.concavityAccepted++;
            trace.outcome = "accepted";
            return new Candidate(retainedMask, retainedLabels, new int[] {0, 1, 2}, 2,
                    "concavity", bestProposalScore);
        } finally {
            contourInput.release();
            hierarchy.release();
            hull.release();
            defects.release();
            barrier.release();
            cut.release();
            labels.release();
            ccStats.release();
            centroids.release();
            for (MatOfPoint c : contours) c.release();
        }
    }


    /**
     * Recursively split the parts produced by the first concavity cut.
     * A chain of four touching balloons needs three cuts; a one-shot binary
     * split can never produce more than two instances regardless of its score.
     * Each additional cut is independently validated before it is kept.
     */
    private static Candidate expandConcavityCandidate(
            Mat original, Mat source, Candidate initial, GeometryStats stats,
            int offsetX, int offsetY, Options options, int componentId,
            int[] remainingSplits) {
        if (initial == null || remainingSplits[0] <= 0) return initial;

        List<Candidate> children = new ArrayList<>();
        int width = original.cols();
        int height = original.rows();
        int[] initialLabels = new int[width * height];
        initial.labels.get(0, 0, initialLabels);
        byte[] initialMask = new byte[width * height];
        initial.mask.get(0, 0, initialMask);

        try {
            for (int part = 1; part <= initial.partCount; part++) {
                byte[] partPixels = new byte[width * height];
                int partArea = 0;
                for (int i = 0; i < initialLabels.length; i++) {
                    if (initialLabels[i] == part && (initialMask[i] & 0xFF) != 0) {
                        partPixels[i] = (byte) 255;
                        partArea++;
                    }
                }
                Mat partMask = new Mat(height, width, CvType.CV_8UC1);
                partMask.put(0, 0, partPixels);

                Candidate child = singlePartCandidate(partMask);
                if (remainingSplits[0] > 0 && partArea >= options.minComponentArea) {
                    Candidate proposal = tryConcavitySplit(partMask, stats,
                            componentId, offsetX, offsetY, options.minPartArea,
                            options.minPartAreaFraction, options.minRetainedFraction);
                    if (proposal != null) {
                        Candidate checked = chooseCombinedCandidate(
                                partMask, source, null, proposal, null, stats, componentId);
                        if (checked == proposal) {
                            remainingSplits[0]--;
                            child.release();
                            child = expandConcavityCandidate(
                                    partMask, source, proposal, stats,
                                    offsetX, offsetY, options, componentId, remainingSplits);
                        } else {
                            proposal.release();
                        }
                    }
                }
                partMask.release();
                children.add(child);
            }

            int totalParts = 0;
            for (Candidate child : children) totalParts += child.partCount;
            Mat mergedMask = Mat.zeros(original.size(), CvType.CV_8UC1);
            int[] mergedLabels = new int[width * height];
            int nextPart = 1;
            double proposalSum = 0.0;
            int proposalCount = 0;
            for (Candidate child : children) {
                int[] childLabels = new int[width * height];
                byte[] childMask = new byte[width * height];
                child.labels.get(0, 0, childLabels);
                child.mask.get(0, 0, childMask);
                for (int i = 0; i < childLabels.length; i++) {
                    int localId = childLabels[i];
                    if (localId <= 0 || (childMask[i] & 0xFF) == 0) continue;
                    int mapped = localId < child.idMap.length ? child.idMap[localId] : 0;
                    if (mapped <= 0 || mapped > child.partCount) continue;
                    mergedLabels[i] = nextPart + mapped - 1;
                }
                Core.bitwise_or(mergedMask, child.mask, mergedMask);
                proposalSum += child.proposalScore * child.partCount;
                proposalCount += child.partCount;
                nextPart += child.partCount;
            }
            Mat labels = new Mat(height, width, CvType.CV_32SC1);
            labels.put(0, 0, mergedLabels);
            int[] idMap = new int[totalParts + 1];
            for (int i = 1; i <= totalParts; i++) idMap[i] = i;
            double score = proposalCount == 0 ? initial.proposalScore
                    : proposalSum / proposalCount;
            Candidate merged = new Candidate(mergedMask, labels, idMap,
                    totalParts, "concavity", score);
            initial.release();
            for (Candidate child : children) child.release();
            children.clear();
            return merged;
        } catch (Throwable error) {
            for (Candidate child : children) child.release();
            throw error;
        }
    }

    private static Candidate singlePartCandidate(Mat partMask) {
        Mat mask = partMask.clone();
        int width = partMask.cols(), height = partMask.rows();
        byte[] pixels = new byte[width * height];
        partMask.get(0, 0, pixels);
        int[] labelsData = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            if ((pixels[i] & 0xFF) != 0) labelsData[i] = 1;
        }
        Mat labels = new Mat(height, width, CvType.CV_32SC1);
        labels.put(0, 0, labelsData);
        return new Candidate(mask, labels, new int[] {0, 1}, 1,
                "concavity", 0.50);
    }

    private static Candidate chooseCombinedCandidate(
            Mat component, Mat source, Candidate boundary, Candidate concavity,
            Candidate geometric, GeometryStats stats, int componentId) {
        Candidate best = null;
        CandidateEvaluation bestEvaluation = null;
        Candidate rejectedBest = null;
        CandidateEvaluation rejectedEvaluation = null;
        String rejectedReason = null;
        List<String> reports = new ArrayList<>();
        Candidate[] candidates = new Candidate[] {boundary, concavity, geometric};
        for (Candidate candidate : candidates) {
            if (candidate == null) continue;
            CandidateEvaluation evaluation = evaluateCandidate(
                    component, source, candidate, concavity, geometric);
            String candidateRejection = candidateRejectionReason(candidate, evaluation);
            reports.add(candidate.method + "=" + evaluation.describe()
                    + (candidateRejection == null ? " [подходит]" : " [отклонён: " + candidateRejection + "]"));
            if (candidateRejection != null) {
                if (rejectedEvaluation == null
                        || evaluation.totalScore > rejectedEvaluation.totalScore) {
                    rejectedBest = candidate;
                    rejectedEvaluation = evaluation;
                    rejectedReason = candidateRejection;
                }
                continue;
            }
            if (bestEvaluation == null || evaluation.totalScore > bestEvaluation.totalScore) {
                best = candidate;
                bestEvaluation = evaluation;
            }
        }
        if (best == null) {
            if (rejectedBest == null) {
                stats.combinedSummaries.add("компонент " + componentId + ": кандидаты не сформированы");
            } else {
                stats.combinedSummaries.add("компонент " + componentId + ": отклонено (" +
                        rejectedReason + "; " + rejectedEvaluation.describe() + "); " +
                        String.join("; ", reports));
            }
            return null;
        }
        stats.combinedSummaries.add("компонент " + componentId + ": принято " +
                best.method + " (" + bestEvaluation.describe() + "); " +
                String.join("; ", reports));
        return best;
    }

    private static String candidateRejectionReason(
            Candidate candidate, CandidateEvaluation evaluation) {
        if (evaluation.crossedGlyphs >= 2 && evaluation.crossedInkFraction >= 0.025) {
            return "разрез проходит через текст";
        }
        if (evaluation.balance < 0.38) return "части слишком несбалансированы";
        if (evaluation.retainedFraction < 0.82) return "потеряно слишком много площади";
        if ("distance/saddle".equals(candidate.method) && evaluation.distanceScore < 0.08) {
            return "нет подтверждения узкой перемычки";
        }
        if (evaluation.totalScore < 0.58) return "недостаточный общий балл";
        return null;
    }

    private static CandidateEvaluation evaluateCandidate(
            Mat component, Mat source, Candidate candidate,
            Candidate concavityCandidate, Candidate geometricCandidate) {
        int width = component.cols();
        int height = component.rows();
        int total = width * height;
        int[] labels = new int[total];
        candidate.labels.get(0, 0, labels);
        int[] partAreas = new int[Math.max(3, candidate.partCount + 1)];
        int retained = 0;
        for (int value : labels) {
            if (value > 0 && value < partAreas.length) {
                partAreas[value]++;
                retained++;
            }
        }
        int minPart = Integer.MAX_VALUE;
        int maxPart = 0;
        for (int id = 1; id <= candidate.partCount && id < partAreas.length; id++) {
            minPart = Math.min(minPart, partAreas[id]);
            maxPart = Math.max(maxPart, partAreas[id]);
        }
        if (minPart == Integer.MAX_VALUE) minPart = 0;
        double balance = maxPart == 0 ? 0.0 : minPart / (double) maxPart;
        int originalArea = Math.max(1, Core.countNonZero(component));
        double retainedFraction = retained / (double) originalArea;
        double areaQuality = 0.58 * balance + 0.42 * retainedFraction;

        double distanceScore = measureDistanceSaddleEvidence(component, labels, candidate.partCount);
        double proposalScore = candidate.proposalScore;
        if (candidate != concavityCandidate && concavityCandidate != null) {
            proposalScore = 0.55 * proposalScore
                    + 0.45 * partitionAgreement(labels, concavityCandidate, total);
        } else if (candidate == concavityCandidate && geometricCandidate != null) {
            proposalScore = 0.70 * proposalScore
                    + 0.30 * partitionAgreement(labels, geometricCandidate, total);
        }

        TextEvidence text = source == null
                ? new TextEvidence(0.35, 0, 0, "исходное изображение недоступно", 0, 0.0)
                : measureTextOnBothSides(source, component, labels, candidate.partCount);
        double proposalWeight;
        double distanceWeight;
        double textWeight;
        double qualityWeight;
        if ("concavity".equals(candidate.method)) {
            // Convexity-defect pairing is itself geometric evidence; requiring
            // a second, unrelated saddle test here wrongly vetoed good concavities.
            proposalWeight = 0.34;
            distanceWeight = 0.12;
            textWeight = 0.24;
            qualityWeight = 0.30;
        } else if ("boundary".equals(candidate.method)) {
            proposalWeight = 0.32;
            distanceWeight = 0.20;
            textWeight = 0.18;
            qualityWeight = 0.30;
        } else {
            proposalWeight = 0.22;
            distanceWeight = 0.30;
            textWeight = 0.18;
            qualityWeight = 0.30;
        }
        double combined = proposalWeight * proposalScore
                + distanceWeight * distanceScore
                + textWeight * text.score
                + qualityWeight * areaQuality;
        return new CandidateEvaluation(candidate.method, combined, proposalScore,
                distanceScore, text.score, areaQuality, balance,
                retainedFraction, text.firstCount, text.secondCount, text.note,
                text.crossedGlyphs, text.crossedInkFraction);
    }

    /**
     * Independent saddle validation of a proposed cut. The seam is the band
     * where the two instance labels approach within four pixels; the median
     * distance-transform radius there is compared with each lobe's peak radius.
     */
    private static double measureDistanceSaddleEvidence(Mat component, int[] labels, int partCount) {
        int width = component.cols();
        int height = component.rows();
        Mat distance = new Mat();
        try {
            Imgproc.distanceTransform(component, distance, Imgproc.DIST_L2, 5);
            float[] values = new float[width * height];
            distance.get(0, 0, values);
            float[] peak = new float[Math.max(3, partCount + 1)];
            List<Float> seamValues = new ArrayList<>();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    int id = labels[index];
                    if (id <= 0 || id >= peak.length) continue;
                    peak[id] = Math.max(peak[id], values[index]);
                    boolean nearOtherPart = false;
                    for (int dy = -4; dy <= 4 && !nearOtherPart; dy++) {
                        for (int dx = -4; dx <= 4; dx++) {
                            if (dx * dx + dy * dy > 16 || (dx == 0 && dy == 0)) continue;
                            int nx = x + dx, ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                            int other = labels[ny * width + nx];
                            if (other > 0 && other != id) {
                                nearOtherPart = true;
                                break;
                            }
                        }
                    }
                    if (nearOtherPart) seamValues.add(values[index]);
                }
            }
            float smallerPeak = Float.POSITIVE_INFINITY;
            for (int id = 1; id <= partCount && id < peak.length; id++) {
                if (peak[id] <= 0) return 0.0;
                smallerPeak = Math.min(smallerPeak, peak[id]);
            }
            if (seamValues.isEmpty() || smallerPeak <= 0) return 0.0;
            Collections.sort(seamValues);
            float medianSeam = seamValues.get(seamValues.size() / 2);
            double ratio = medianSeam / smallerPeak;
            return clamp01(1.0 - ratio / 0.85);
        } finally {
            distance.release();
        }
    }

    /** Agreement between independently proposed two-way partitions (labels may be reversed). */
    private static double partitionAgreement(int[] candidateLabels, Candidate other, int total) {
        int[] otherLabels = new int[total];
        other.labels.get(0, 0, otherLabels);
        int direct = 0, reversed = 0, eligible = 0;
        for (int i = 0; i < total; i++) {
            int a = candidateLabels[i], b = otherLabels[i];
            if (a <= 0 || b <= 0) continue;
            eligible++;
            if (a == b) direct++;
            if ((a == 1 && b == 2) || (a == 2 && b == 1)) reversed++;
        }
        if (eligible == 0) return 0.0;
        return Math.max(direct, reversed) / (double) eligible;
    }

    /**
     * OpenCV-only text proxy for the standalone APK: Otsu dark-ink components
     * inside each proposed region. It does not claim OCR recognition; it checks
     * whether glyph-sized marks occur on both sides of a proposed cut.
     */
    private static TextEvidence measureTextOnBothSides(
            Mat source, Mat component, int[] partLabels, int partCount) {
        Mat gray = new Mat();
        Mat ink = new Mat();
        Mat ccLabels = new Mat();
        Mat ccStats = new Mat();
        Mat centroids = new Mat();
        try {
            if (source.channels() == 1) source.copyTo(gray);
            else if (source.channels() == 4) Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGRA2GRAY);
            else Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.threshold(gray, ink, 0, 255,
                    Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
            Core.bitwise_and(ink, component, ink);
            int ccCount = Imgproc.connectedComponentsWithStats(
                    ink, ccLabels, ccStats, centroids, 8, CvType.CV_32S);
            int width = component.cols(), height = component.rows();
            int[] cc = new int[width * height];
            ccLabels.get(0, 0, cc);
            int[] glyphCounts = new int[Math.max(3, partCount + 1)];
            int componentArea = Math.max(1, Core.countNonZero(component));
            int maxGlyphArea = Math.max(100, (int) (componentArea * 0.025));
            int eligibleGlyphs = 0;
            int crossedGlyphs = 0;
            int eligibleInkPixels = 0;
            int crossedInkPixels = 0;
            for (int id = 1; id < ccCount; id++) {
                int area = (int) ccStats.get(id, Imgproc.CC_STAT_AREA)[0];
                int x = (int) ccStats.get(id, Imgproc.CC_STAT_LEFT)[0];
                int y = (int) ccStats.get(id, Imgproc.CC_STAT_TOP)[0];
                int w = (int) ccStats.get(id, Imgproc.CC_STAT_WIDTH)[0];
                int h = (int) ccStats.get(id, Imgproc.CC_STAT_HEIGHT)[0];
                if (area < 2 || area > maxGlyphArea
                        || w > Math.max(30, width * 0.30)
                        || h > Math.max(26, height * 0.24)
                        || w > Math.max(12, h * 12)) continue;
                int[] perPart = new int[glyphCounts.length];
                int assignedPixels = 0;
                for (int yy = y; yy < y + h; yy++) {
                    int row = yy * width;
                    for (int xx = x; xx < x + w; xx++) {
                        if (cc[row + xx] != id) continue;
                        int part = partLabels[row + xx];
                        if (part > 0 && part < perPart.length) {
                            perPart[part]++;
                            assignedPixels++;
                        }
                    }
                }
                if (assignedPixels < 2) continue;
                eligibleGlyphs++;
                eligibleInkPixels += assignedPixels;
                int bestPart = 0, bestPixels = 0, secondPixels = 0;
                for (int part = 1; part <= partCount && part < perPart.length; part++) {
                    int count = perPart[part];
                    if (count > bestPixels) {
                        secondPixels = bestPixels;
                        bestPixels = count;
                        bestPart = part;
                    } else if (count > secondPixels) {
                        secondPixels = count;
                    }
                }
                // A connected ink component assigned substantially to multiple
                // instance IDs means the proposed boundary cut through a glyph.
                if (secondPixels >= Math.max(3, (int) Math.ceil(assignedPixels * 0.12))) {
                    crossedGlyphs++;
                    crossedInkPixels += assignedPixels;
                }
                if (bestPart > 0 && bestPixels >= Math.max(2, (int) (assignedPixels * 0.35))) {
                    glyphCounts[bestPart]++;
                }
            }
            int first = glyphCounts.length > 1 ? glyphCounts[1] : 0;
            int second = glyphCounts.length > 2 ? glyphCounts[2] : 0;
            int minCount = Integer.MAX_VALUE, maxCount = 0;
            for (int part = 1; part <= partCount && part < glyphCounts.length; part++) {
                minCount = Math.min(minCount, glyphCounts[part]);
                maxCount = Math.max(maxCount, glyphCounts[part]);
            }
            if (minCount == Integer.MAX_VALUE) minCount = 0;
            double score;
            if (maxCount == 0) score = 0.35; // no reliable text-like marks: neutral, not an automatic veto
            else if (minCount == 0) score = 0.0;
            else {
                double balance = minCount / (double) maxCount;
                double quantity = Math.min(1.0, minCount / 3.0);
                score = 0.60 * balance + 0.40 * quantity;
            }
            double crossedFraction = eligibleInkPixels == 0 ? 0.0
                    : crossedInkPixels / (double) eligibleInkPixels;
            score *= Math.max(0.0, 1.0 - 3.0 * crossedFraction);
            String note = String.format(java.util.Locale.US,
                    "ink-components; cut=%d/%d, %.1f%%",
                    crossedGlyphs, eligibleGlyphs, crossedFraction * 100.0);
            return new TextEvidence(score, first, second, note,
                    crossedGlyphs, crossedFraction);
        } finally {
            gray.release();
            ink.release();
            ccLabels.release();
            ccStats.release();
            centroids.release();
        }
    }

    /**
     * Minimum-cost path constrained to the foreground. Moving through thick
     * lobe interiors is expensive; moving through a narrow neck (small distance
     * to the exterior) is cheap. The returned pixel indices describe a curved seam.
     */
    private static List<Integer> shortestNeckPath(byte[] mask, float[] distance,
            int width, int height, int sx, int sy, int ex, int ey) {
        if (sx < 0 || sy < 0 || ex < 0 || ey < 0
                || sx >= width || ex >= width || sy >= height || ey >= height) return null;
        int start = sy * width + sx;
        int end = ey * width + ex;
        if ((mask[start] & 0xFF) == 0 || (mask[end] & 0xFF) == 0) return null;
        int total = width * height;
        double[] cost = new double[total];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        int[] parent = new int[total];
        Arrays.fill(parent, -1);
        PriorityQueue<NeckNode> queue = new PriorityQueue<>(new Comparator<NeckNode>() {
            @Override public int compare(NeckNode a, NeckNode b) {
                return Double.compare(a.cost, b.cost);
            }
        });
        cost[start] = 0.0;
        queue.add(new NeckNode(start, 0.0));
        final int[] dxs = {-1, 0, 1, -1, 1, -1, 0, 1};
        final int[] dys = {-1, -1, -1, 0, 0, 1, 1, 1};
        while (!queue.isEmpty()) {
            NeckNode node = queue.poll();
            if (node.cost != cost[node.index]) continue;
            if (node.index == end) break;
            int x = node.index % width;
            int y = node.index / width;
            for (int d = 0; d < dxs.length; d++) {
                int nx = x + dxs[d], ny = y + dys[d];
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                int next = ny * width + nx;
                if ((mask[next] & 0xFF) == 0) continue;
                double step = (dxs[d] == 0 || dys[d] == 0) ? 1.0 : 1.41421356237;
                double radius = Math.max(0.0, distance[next]);
                double nextCost = node.cost + step * (1.0 + 0.45 * radius * radius);
                if (nextCost < cost[next]) {
                    cost[next] = nextCost;
                    parent[next] = node.index;
                    queue.add(new NeckNode(next, nextCost));
                }
            }
        }
        if (parent[end] < 0 && end != start) return null;
        ArrayList<Integer> path = new ArrayList<>();
        int cursor = end;
        path.add(cursor);
        while (cursor != start) {
            cursor = parent[cursor];
            if (cursor < 0 || path.size() > total) return null;
            path.add(cursor);
        }
        Collections.reverse(path);
        return path;
    }

    private static final class NeckNode {
        final int index;
        final double cost;
        NeckNode(int index, double cost) { this.index = index; this.cost = cost; }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final class TextEvidence {
        final double score;
        final int firstCount, secondCount, crossedGlyphs;
        final double crossedInkFraction;
        final String note;
        TextEvidence(double score, int firstCount, int secondCount, String note,
                int crossedGlyphs, double crossedInkFraction) {
            this.score = score;
            this.firstCount = firstCount;
            this.secondCount = secondCount;
            this.note = note;
            this.crossedGlyphs = crossedGlyphs;
            this.crossedInkFraction = crossedInkFraction;
        }
    }

    private static final class CandidateEvaluation {
        final String method;
        final double totalScore, concavityScore, distanceScore, textScore, areaQuality;
        final double balance, retainedFraction;
        final int firstTextCount, secondTextCount, crossedGlyphs;
        final double crossedInkFraction;
        final String textNote;
        CandidateEvaluation(String method, double totalScore, double concavityScore,
                double distanceScore, double textScore, double areaQuality,
                double balance, double retainedFraction, int firstTextCount,
                int secondTextCount, String textNote, int crossedGlyphs,
                double crossedInkFraction) {
            this.method = method;
            this.totalScore = totalScore;
            this.concavityScore = concavityScore;
            this.distanceScore = distanceScore;
            this.textScore = textScore;
            this.areaQuality = areaQuality;
            this.balance = balance;
            this.retainedFraction = retainedFraction;
            this.firstTextCount = firstTextCount;
            this.secondTextCount = secondTextCount;
            this.textNote = textNote;
            this.crossedGlyphs = crossedGlyphs;
            this.crossedInkFraction = crossedInkFraction;
        }
        String describe() {
            return String.format(java.util.Locale.US,
                    "score=%.3f [proposal=%.2f, saddle=%.2f, text=%.2f (%d/%d %s), result=%.2f, balance=%.2f, retained=%.2f]",
                    totalScore, concavityScore, distanceScore, textScore,
                    firstTextCount, secondTextCount,
                    textNote + String.format(java.util.Locale.US,
                            " [crossed=%d, ink=%.1f%%]",
                            crossedGlyphs, crossedInkFraction * 100.0),
                    areaQuality, balance, retainedFraction);
        }
    }

    private static double triangleArea(Point a, Point b, Point c) {
        return Math.abs((a.x * (b.y - c.y) + b.x * (c.y - a.y)
                + c.x * (a.y - b.y)) * 0.5);
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

    /** Public immutable-ish diagnostic point; coordinates are in the source bitmap. */
    public static final class PeakInfo {
        public final int x;
        public final int y;
        public final float radius;
        PeakInfo(int x, int y, float radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }

    /** One attempted source component, including the selected centers and watershed result. */
    public static final class GeometryTrace {
        public final int componentId;
        public final int componentX, componentY, componentWidth, componentHeight, componentArea;
        public final List<PeakInfo> peaks = new ArrayList<>();
        public int rawPeakComponents;
        public int separatedPeaks;
        public int firstX = -1, firstY = -1, secondX = -1, secondY = -1;
        public int saddleX = -1, saddleY = -1;
        public double saddleRatio = Double.NaN;
        public int boundaryStrength;
        public int firstArea, secondArea, minimumPartArea, retainedArea;
        public double retainedFraction;
        public String outcome = "not_completed";
        GeometryTrace(int componentId, int x, int y, int width, int height, int area) {
            this.componentId = componentId;
            componentX = x; componentY = y; componentWidth = width;
            componentHeight = height; componentArea = area;
        }
    }

    public static final class ConcavityTrace {
        public final int componentId;
        ConcavityTrace(int componentId) { this.componentId = componentId; }
        public int x1 = -1, y1 = -1, x2 = -1, y2 = -1;
        public int firstArea, secondArea, retainedArea;
        public int defectCount, deepDefects, facingPairs, neckPaths, disconnectedCuts;
        public double triangleArea;
        public String outcome = "not_completed";
    }

    private static final class GeometryStats {
        final List<GeometryTrace> traces = new ArrayList<>();
        final List<ConcavityTrace> concavityTraces = new ArrayList<>();
        int attempts;
        int rejectedTooSmall;
        int rejectedNoPeaks;
        int rejectedNoPath;
        int rejectedNeckTooWide;
        int rejectedPartTooSmall;
        int rejectedRetainedArea;
        int accepted;
        int concavityAttempts, concavityAccepted, concavityRejectedNoDefects, concavityRejectedGeometry;
        double concavityBestDepth;
        int concavityBestRetained, concavityBestAreaA, concavityBestAreaB;
        int concavityFirstX = -1, concavityFirstY = -1, concavitySecondX = -1, concavitySecondY = -1;
        int[] concavityLabels;
        byte[] concavityMask;
        double bestSaddleRatio = Double.POSITIVE_INFINITY;
        int bestBoundary;
        final List<String> combinedSummaries = new ArrayList<>();

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
                    + " / порог " + Math.round(options.geometryMaxSaddleRatio * 100.0) + "%"
                    + ", радиус центра ≥ " + options.geometryMinPeakRadius + " px"
                    + ", расстояние между центрами ≥ " + options.geometryMinPeakDistance + " px"
                    + ", boundary start = " + options.boundaryThresholdStart
                    + "; concavity: попыток " + concavityAttempts + ", разделено " + concavityAccepted
                    + ", нет глубоких дефектов " + concavityRejectedNoDefects
                    + ", неподходящая геометрия " + concavityRejectedGeometry
                    + "; объединённая оценка (вогнутости + saddle + текст + качество результата): "
                    + (combinedSummaries.isEmpty() ? "нет подходящих компонентов"
                        : String.join(" | ", combinedSummaries));
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
        final String method;
        final double proposalScore;

        Candidate(Mat mask, Mat labels, int[] idMap, int partCount,
                  String method, double proposalScore) {
            this.mask = mask;
            this.labels = labels;
            this.idMap = idMap;
            this.partCount = partCount;
            this.method = method;
            this.proposalScore = clamp01(proposalScore);
        }

        void release() {
            mask.release();
            labels.release();
        }
    }
}
