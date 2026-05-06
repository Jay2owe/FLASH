package flash.pipeline.image;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe measurement utilities that bypass ImageJ's global ResultsTable.
 *
 * All methods create local ImageStatistics objects and return results directly,
 * avoiding the shared ResultsTable singleton used by IJ.run("Measure").
 */
public final class ThreadSafeMeasure {

    private ThreadSafeMeasure() {}

    /** Result for a single slice measurement. */
    public static final class SliceResult {
        public final double intDen;
        public final double areaFraction;
        public final double rawIntDen;

        public SliceResult(double intDen, double areaFraction, double rawIntDen) {
            this.intDen = intDen;
            this.areaFraction = areaFraction;
            this.rawIntDen = rawIntDen;
        }
    }

    /**
     * Measures IntDen and %Area on the filtered/signal channel, plus raw IntDen
     * on the raw channel, for a single slice.
     *
     * @param signalImp  filtered (possibly binarized) channel image
     * @param rawImp     raw (unfiltered) channel image
     * @param slice      1-based slice index
     * @param roi        optional ROI to restrict measurement (may be null)
     * @return measurement result
     */
    public static SliceResult measureSlice(ImagePlus signalImp, ImagePlus rawImp, int slice, Roi roi) {
        // Signal channel: IntDen + %Area
        ImageProcessor sigIp = signalImp.getStack().getProcessor(slice);
        if (roi != null) sigIp.setRoi(roi);
        int measurements = ij.measure.Measurements.INTEGRATED_DENSITY | ij.measure.Measurements.AREA_FRACTION;
        Calibration sigCal = signalImp.getCalibration();
        ImageStatistics sigStats = ImageStatistics.getStatistics(sigIp, measurements, sigCal);
        double intDen = sigStats.area > 0 ? sigStats.mean * sigStats.area : 0.0;
        double areaFraction = sigStats.areaFraction;

        // Raw channel: IntDen only
        double rawIntDen = 0;
        if (rawImp != null) {
            ImageProcessor rawIp = rawImp.getStack().getProcessor(slice);
            if (roi != null) rawIp.setRoi(roi);
            int rawMeas = ij.measure.Measurements.INTEGRATED_DENSITY;
            Calibration rawCal = rawImp.getCalibration();
            ImageStatistics rawStats = ImageStatistics.getStatistics(rawIp, rawMeas, rawCal);
            rawIntDen = rawStats.area > 0 ? rawStats.mean * rawStats.area : 0.0;
        }

        return new SliceResult(intDen, areaFraction, rawIntDen);
    }

    /**
     * Measures all slices and returns arrays of results.
     *
     * @param signalImp  filtered channel
     * @param rawImp     raw channel
     * @param roi        optional ROI (may be null)
     * @return array of SliceResult, one per slice
     */
    /** Minimum number of slices to use parallel measurement. */
    private static final int PARALLEL_THRESHOLD = 4;

    public static SliceResult[] measureAllSlices(ImagePlus signalImp, ImagePlus rawImp, Roi roi) {
        int nSlices = signalImp.getNSlices();
        SliceResult[] results = new SliceResult[nSlices];

        if (nSlices < PARALLEL_THRESHOLD) {
            for (int s = 1; s <= nSlices; s++) {
                results[s - 1] = measureSlice(signalImp, rawImp, s, roi);
            }
            return results;
        }

        int nThreads = Math.min(nSlices, Runtime.getRuntime().availableProcessors());
        ExecutorService exec = Executors.newFixedThreadPool(nThreads);
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int s = 1; s <= nSlices; s++) {
                final int slice = s;
                final Roi roiClone = (roi != null) ? (Roi) roi.clone() : null;
                futures.add(exec.submit(new Runnable() {
                    @Override
                    public void run() {
                        results[slice - 1] = measureSlice(signalImp, rawImp, slice, roiClone);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    throw new RuntimeException("Parallel measurement failed", e);
                }
            }
        } finally {
            exec.shutdown();
        }
        return results;
    }
}
