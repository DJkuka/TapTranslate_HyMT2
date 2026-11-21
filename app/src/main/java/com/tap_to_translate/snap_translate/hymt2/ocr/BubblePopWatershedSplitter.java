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
import java.util.PriorityQueue;

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
 *  4. Use those peaks as markers for OpenCV watershed. Its terrain comes from
 *     the inverse distance transform plus BubblePop boundary evidence; artwork
 *     intensity is deliberately excluded so text strokes cannot create cuts.
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
    private static final double MIN_PEAK_DISTANCE = 16.0;
    private static final double SAME_BUBBLE_SADDLE_RATIO = 0.62;
    private static final double MAX_BOUNDARY_SUPPORTED_SADDLE_RATIO = 0.82;
    private static final double MIN_ACCEPTED_REGION_FRACTION = 0.10;
    private static final int MAX_SPLIT_SEEDS = 4;
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
                    component, distance.cols(), distance.rows());
            if (seeds.size() < 2) {
                return 0;
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

            Mat watershedImage = buildWatershedImage(component, boundaryMap, rect);
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
                    (int) (Core.countNonZero(component) * MIN_ACCEPTED_REGION_FRACTION));
            int substantialRegions = 0;
            for (int label = 2; label < regionAreas.length; label++) {
                if (regionAreas[label] >= minRegionArea) {
                    substantialRegions++;
                }
            }
            if (substantialRegions < 2) {
                return 0;
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

    /**
     * Merge local distance maxima that belong to the same broad lobe.
     *
     * The saddle is measured along the best available path INSIDE the mask,
     * not on the straight segment between two peaks. This handles curved and
     * offset bridges and prevents one irregular bubble from producing many
     * watershed markers.
     */
    private static List<Peak> selectSeparatedPeaks(
            List<Peak> peaks, float[] distance, byte[] boundary, Mat component,
            int width, int height) {
        int n = peaks.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        byte[] mask = new byte[width * height];
        component.get(0, 0, mask);

        for (int i = 0; i < n; i++) {
            Peak a = peaks.get(i);
            for (int j = i + 1; j < n; j++) {
                Peak b = peaks.get(j);
                float saddle = widestPathSaddle(a, b, distance, mask, width, height);
                double ratio = saddle / Math.max(1.0, Math.min(a.radius, b.radius));
                int boundaryStrength = maxBoundaryNear(boundary, bresenhamMidX(a, b),
                        bresenhamMidY(a, b), width, height, 5);

                boolean boundarySeparates = ratio <= MAX_BOUNDARY_SUPPORTED_SADDLE_RATIO
                        && boundaryStrength >= BOUNDARY_EVIDENCE;
                boolean geometricallySeparate = ratio <= SAME_BUBBLE_SADDLE_RATIO;
                if (!boundarySeparates && !geometricallySeparate) {
                    union(parent, i, j);
                }
            }
        }

        // One representative (the strongest distance maximum) per merged lobe.
        List<Peak> representatives = new ArrayList<>();
        boolean[] emitted = new boolean[n];
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            if (emitted[root]) continue;
            emitted[root] = true;
            Peak best = peaks.get(i);
            for (int j = i + 1; j < n; j++) {
                if (find(parent, j) == root && peaks.get(j).radius > best.radius) {
                    best = peaks.get(j);
                }
            }
            representatives.add(best);
        }
        Collections.sort(representatives, new Comparator<Peak>() {
            @Override public int compare(Peak a, Peak b) {
                return Float.compare(b.radius, a.radius);
            }
        });
        if (representatives.size() > MAX_SPLIT_SEEDS) {
            representatives = new ArrayList<>(representatives.subList(0, MAX_SPLIT_SEEDS));
        }
        return representatives;
    }

    /** Maximin path: the minimum radius along the widest path between peaks. */
    private static float widestPathSaddle(Peak start, Peak target, float[] distance,
                                          byte[] mask, int width, int height) {
        int total = width * height;
        int sourceIndex = start.y * width + start.x;
        int targetIndex = target.y * width + target.x;
        float[] best = new float[total];
        java.util.Arrays.fill(best, -1f);
        PriorityQueue<PathNode> queue = new PriorityQueue<>(new Comparator<PathNode>() {
            @Override public int compare(PathNode a, PathNode b) {
                return Float.compare(b.capacity, a.capacity);
            }
        });
        best[sourceIndex] = distance[sourceIndex];
        queue.add(new PathNode(sourceIndex, best[sourceIndex]));

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            if (current.capacity < best[current.index]) continue;
            if (current.index == targetIndex) return current.capacity;
            int x = current.index % width;
            int y = current.index / width;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    int ni = ny * width + nx;
                    if ((mask[ni] & 0xFF) == 0) continue;
                    float capacity = Math.min(current.capacity, distance[ni]);
                    if (capacity > best[ni]) {
                        best[ni] = capacity;
                        queue.add(new PathNode(ni, capacity));
                    }
                }
            }
        }
        return 0f;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[rb] = ra;
    }

    private static int bresenhamMidX(Peak a, Peak b) { return (a.x + b.x) / 2; }
    private static int bresenhamMidY(Peak a, Peak b) { return (a.y + b.y) / 2; }

    private static final class PathNode {
        final int index;
        final float capacity;
        PathNode(int index, float capacity) {
            this.index = index;
            this.capacity = capacity;
        }
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

    /**
     * Builds a watershed terrain from mask geometry, never from comic artwork.
     * The inverse distance transform creates basins around bubble centers and
     * ridges at narrow necks; BubblePop boundary probabilities reinforce those
     * ridges where the model has useful evidence.
     */
    private static Mat buildWatershedImage(Mat component, Mat boundaryMap, Rect rect) {
        Mat distance = new Mat();
        Mat inverseDistance = new Mat();
        Mat boundaryRoi = boundaryMap.submat(rect);
        Mat boundary8 = new Mat();
        Mat terrain = new Mat();
        Mat bgr = new Mat();
        try {
            Imgproc.distanceTransform(component, distance, Imgproc.DIST_L2, 5);
            Core.normalize(distance, inverseDistance, 255.0, 0.0, Core.NORM_MINMAX, CvType.CV_8UC1);
            if (boundaryRoi.depth() == CvType.CV_8U) {
                boundaryRoi.copyTo(boundary8);
            } else {
                double scale = boundaryRoi.depth() == CvType.CV_32F
                        || boundaryRoi.depth() == CvType.CV_64F ? 255.0 : 1.0;
                boundaryRoi.convertTo(boundary8, CvType.CV_8UC1, scale);
            }
            // Inverse distance is the geometric terrain. Boundary evidence only
            // strengthens likely cuts; text strokes and artwork intensity are absent.
            Core.addWeighted(inverseDistance, 0.72, boundary8, 0.90, 0.0, terrain);
            Imgproc.cvtColor(terrain, bgr, Imgproc.COLOR_GRAY2BGR);
            return bgr;
        } catch (Throwable t) {
            bgr.release();
            throw t;
        } finally {
            distance.release();
            inverseDistance.release();
            boundaryRoi.release();
            boundary8.release();
            terrain.release();
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
