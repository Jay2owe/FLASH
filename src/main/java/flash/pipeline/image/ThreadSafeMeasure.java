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
        public final double intDenFilteredFullRoi;
        public final double areaFractionFilteredFullRoi;
        public final double intDenUnfilteredFullRoi;
        public final double intDenBinarizedRawInMask;
        public final double areaFractionBinarized;
        public final boolean hasBinarizedMeasurement;

        public SliceResult(double intDenFilteredFullRoi,
                           double areaFractionFilteredFullRoi,
                           double intDenUnfilteredFullRoi,
                           double intDenBinarizedRawInMask,
                           double areaFractionBinarized,
                           boolean hasBinarizedMeasurement) {
            this.intDenFilteredFullRoi = intDenFilteredFullRoi;
            this.areaFractionFilteredFullRoi = areaFractionFilteredFullRoi;
            this.intDenUnfilteredFullRoi = intDenUnfilteredFullRoi;
            this.intDenBinarizedRawInMask = intDenBinarizedRawInMask;
            this.areaFractionBinarized = areaFractionBinarized;
            this.hasBinarizedMeasurement = hasBinarizedMeasurement;
        }
    }

    /**
     * Measures raw-first intensity values for a single slice.
     *
     * @param filteredImp              filtered full-ROI channel image
     * @param rawImp                   raw (unfiltered) channel image
     * @param binarizedRawInMaskImp    optional raw image after filter-threshold mask
     * @param slice                    1-based slice index
     * @param roi                      optional ROI to restrict measurement (may be null)
     * @return measurement result
     */
    public static SliceResult measureSlice(ImagePlus filteredImp,
                                           ImagePlus rawImp,
                                           ImagePlus binarizedRawInMaskImp,
                                           int slice,
                                           Roi roi) {
        IntegratedAreaResult filtered = measureIntegratedDensityAndAreaFraction(filteredImp, slice, roi);

        double intDenUnfiltered = 0.0;
        if (rawImp != null) {
            intDenUnfiltered = measureIntegratedDensity(rawImp, slice, roi);
        }

        boolean hasBinarizedMeasurement = binarizedRawInMaskImp != null;
        double intDenBinarized = Double.NaN;
        double areaFractionBinarized = Double.NaN;
        if (hasBinarizedMeasurement) {
            IntegratedAreaResult binarized =
                    measureIntegratedDensityAndAreaFraction(binarizedRawInMaskImp, slice, roi);
            intDenBinarized = binarized.intDen;
            areaFractionBinarized = binarized.areaFraction;
        }

        return new SliceResult(filtered.intDen, filtered.areaFraction, intDenUnfiltered,
                intDenBinarized, areaFractionBinarized, hasBinarizedMeasurement);
    }

    /**
     * Measures all slices and returns arrays of results.
     *
     * @param filteredImp            filtered full-ROI channel
     * @param rawImp                 raw channel
     * @param binarizedRawInMaskImp  optional raw image after filter-threshold mask
     * @param roi                    optional ROI (may be null)
     * @return array of SliceResult, one per slice
     */
    /** Minimum number of slices to use parallel measurement. */
    private static final int PARALLEL_THRESHOLD = 4;

    public static SliceResult[] measureAllSlices(ImagePlus filteredImp,
                                                 ImagePlus rawImp,
                                                 ImagePlus binarizedRawInMaskImp,
                                                 Roi roi) {
        int nSlices = filteredImp.getNSlices();
        SliceResult[] results = new SliceResult[nSlices];

        if (nSlices < PARALLEL_THRESHOLD) {
            for (int s = 1; s <= nSlices; s++) {
                results[s - 1] = measureSlice(filteredImp, rawImp, binarizedRawInMaskImp, s, roi);
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
                        results[slice - 1] = measureSlice(filteredImp, rawImp,
                                binarizedRawInMaskImp, slice, roiClone);
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

    private static IntegratedAreaResult measureIntegratedDensityAndAreaFraction(
            ImagePlus imp, int slice, Roi roi) {
        ImageProcessor ip = imp.getStack().getProcessor(slice).duplicate();
        if (roi != null) ip.setRoi(roi);
        int measurements = ij.measure.Measurements.INTEGRATED_DENSITY
                | ij.measure.Measurements.AREA_FRACTION;
        Calibration cal = imp.getCalibration();
        ImageStatistics stats = ImageStatistics.getStatistics(ip, measurements, cal);
        double intDen = stats.area > 0 ? stats.mean * stats.area : 0.0;
        return new IntegratedAreaResult(finiteOrNaN(intDen), finiteOrNaN(stats.areaFraction));
    }

    private static double measureIntegratedDensity(ImagePlus imp, int slice, Roi roi) {
        ImageProcessor ip = imp.getStack().getProcessor(slice).duplicate();
        if (roi != null) ip.setRoi(roi);
        int measurements = ij.measure.Measurements.INTEGRATED_DENSITY;
        Calibration cal = imp.getCalibration();
        ImageStatistics stats = ImageStatistics.getStatistics(ip, measurements, cal);
        double intDen = stats.area > 0 ? stats.mean * stats.area : 0.0;
        return finiteOrNaN(intDen);
    }

    private static double finiteOrNaN(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? Double.NaN : value;
    }

    private static final class IntegratedAreaResult {
        final double intDen;
        final double areaFraction;

        IntegratedAreaResult(double intDen, double areaFraction) {
            this.intDen = intDen;
            this.areaFraction = areaFraction;
        }
    }
}
