package com.tap_to_translate.snap_translate.hymt2.ocr;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

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
    private static final int[] BOUNDARY_THRESHOLDS = {176, 160, 144, 128, 112};
    private static final int MIN_COMPONENT_AREA = 700;
    private static final int MIN_PART_AREA = 120;
    private static final int MAX_PARTS = 4;
    private static final double MIN_RETAINED_FRACTION = 0.82;
    private static final int CLOSE_KERNEL = 3;

    private BubblePopBoundarySplitter() {}

    public static final class Result {
        public final Mat mask;
        /** CV_32SC1: a positive, unique ID for each retained bubble instance. */
        public final Mat instances;
        public final int instanceCount;
        public final int inputComponents;
        public final int outputComponents;
        public final int splitComponents;
        public final int boundaryThreshold;

        private Result(Mat mask, Mat instances, int instanceCount,
                       int inputComponents, int outputComponents,
                       int splitComponents, int boundaryThreshold) {
            this.mask = mask;
            this.instances = instances;
            this.instanceCount = instanceCount;
            this.inputComponents = inputComponents;
            this.outputComponents = outputComponents;
            this.splitComponents = splitComponents;
            this.boundaryThreshold = boundaryThreshold;
        }
    }

    /** Caller must release both returned Mats. */
    public static Result split(Mat inputMask, Mat boundaryMap) {
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

            Mat closeKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, new Size(CLOSE_KERNEL, CLOSE_KERNEL));
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
                    if (area >= MIN_COMPONENT_AREA) {
                        Mat boundaryRoi = boundary8.submat(rect);
                        try {
                            for (int threshold : BOUNDARY_THRESHOLDS) {
                                Candidate candidate = tryBoundarySplit(
                                        component, boundaryRoi, threshold, closeKernel);
                                if (candidate != null) {
                                    accepted = candidate;
                                    usedThreshold = usedThreshold < 0
                                            ? threshold : Math.min(usedThreshold, threshold);
                                    break;
                                }
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
                    count, splitCount, usedThreshold);
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
            Mat component, Mat boundaryRoi, int threshold, Mat closeKernel) {
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
            if (count < 3 || count - 1 > MAX_PARTS) return null;

            int minArea = Math.max(MIN_PART_AREA,
                    (int) Math.round(Core.countNonZero(component) * 0.04));
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
            if (accepted < 2 || accepted > MAX_PARTS
                    || retainedArea < originalArea * MIN_RETAINED_FRACTION) {
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
