package flash.pipeline.intelligence;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;

/**
 * Pixel-level "Check my data" diagnostics. Opens files and computes
 * deterministic statistics — no ML, no thresholds-as-decisions.
 *
 * Only called when the user ticks "also check image quality" in the
 * Diagnostics dialog. Samples up to {@link #MAX_FILES} files to keep
 * runtime bounded on large cohorts.
 *
 * Implements:
 *   L-05 Dynamic-range report (utilised fraction, saturation fraction)
 *   L-06 Tissue-coverage report (edge density)
 *   B-05 SNR score (Otsu-gated foreground/background)
 *   I-01 Background-uniformity check (tile-grid CV of below-threshold medians)
 *   I-05 Too-clean / too-noisy CV flagger
 *
 * The I-04 Odd-Image-Out and P-16 Histogram-Shape outlier checks are
 * produced by comparing the per-image summaries collected here.
 */
public final class ImageQualityDiagnostics {

    public static final int MAX_FILES = 12;   // cap the sample per run

    private static final Set<String> IMG_EXTS = new HashSet<String>(
            Arrays.asList("lif", "czi", "nd2", "tif", "tiff"));

    private ImageQualityDiagnostics() {}

    public static final class ChannelStats {
        public int channel;
        public int bitDepth;
        public int ceiling;            // L-01 real saturation ceiling
        public double utilisation;     // L-05
        public double saturationFrac;  // L-05
        public double snrDb;           // B-05
        public double tissueCoverage;  // L-06
        public double bgUniformityCV;  // I-01 (lower = more uniform)
        public double pixelCV;         // I-05 (SD/mean on raw)
    }

    public static final class FileStats {
        public String fileName;
        public int width, height, slices, channels;
        public List<ChannelStats> perChannel = new ArrayList<ChannelStats>();
    }

    public static void run(String directory, DiagnosticsReport report) {
        File dir = new File(directory == null ? "" : directory);
        if (!dir.isDirectory()) {
            DiagnosticsReport.Section s = report.addSection("Image quality");
            s.error("Directory not found.");
            return;
        }

        File[] visibleFiles = JunkFileFilter.listCleanFiles(dir);
        List<File> candidateList = new ArrayList<File>();
        for (File candidate : visibleFiles) {
            String lower = candidate.getName().toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            if (dot < 0) continue;
            if (IMG_EXTS.contains(lower.substring(dot + 1))) {
                candidateList.add(candidate);
            }
        }
        if (candidateList.isEmpty()) {
            DiagnosticsReport.Section s = report.addSection("Image quality");
            s.info("No raw images to check.");
            return;
        }
        File[] candidates = candidateList.toArray(new File[candidateList.size()]);
        Arrays.sort(candidates);
        File[] sample = candidates.length <= MAX_FILES
                ? candidates
                : Arrays.copyOf(candidates, MAX_FILES);

        DiagnosticsReport.Section scanSec = report.addSection("Image-quality scan");
        scanSec.info("Sampling " + sample.length + " of " + candidates.length
                + " image file(s). Opening images...");

        List<FileStats> all = new ArrayList<FileStats>();
        for (File f : sample) {
            try {
                FileStats fs = analyseFile(f);
                if (fs != null) all.add(fs);
            } catch (Throwable t) {
                scanSec.warn(f.getName() + " -- could not analyse: " + t.getMessage());
            }
        }

        if (all.isEmpty()) {
            scanSec.warn("No files could be analysed.");
            return;
        }

        renderDynamicRange(all, report.addSection("Dynamic range & saturation (L-05)"));
        renderTissueCoverage(all, report.addSection("Tissue coverage (L-06)"));
        renderSnr(all, report.addSection("Signal-to-noise (B-05)"));
        renderUniformity(all, report.addSection("Background uniformity (I-01)"));
        renderTooCleanTooNoisy(all, report.addSection("Clean/noisy (I-05)"));
        renderHistogramOutliers(all, report.addSection("Outlier detection - histogram shape (P-16)"));
    }

    // ── File-level analysis ──────────────────────────

    private static FileStats analyseFile(File f) throws Exception {
        ImporterOptions opts = new ImporterOptions();
        opts.setId(f.getAbsolutePath());
        opts.setWindowless(true);
        opts.setSplitChannels(false);
        opts.setSplitTimepoints(false);
        opts.setSplitFocalPlanes(false);
        opts.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT);
        opts.setOpenAllSeries(false);
        opts.setVirtual(true);

        ImagePlus[] imps = BF.openImagePlus(opts);
        if (imps == null || imps.length == 0) return null;
        ImagePlus imp = imps[0];
        try {
            return statsFor(imp, f.getName());
        } finally {
            imp.close();
            for (int i = 1; i < imps.length; i++) imps[i].close();
        }
    }

    private static FileStats statsFor(ImagePlus imp, String fileName) {
        FileStats fs = new FileStats();
        fs.fileName = fileName;
        fs.width = imp.getWidth();
        fs.height = imp.getHeight();
        fs.channels = imp.getNChannels();
        fs.slices = imp.getNSlices();

        int midZ = Math.max(1, fs.slices / 2);
        for (int c = 1; c <= fs.channels; c++) {
            imp.setPositionWithoutUpdate(c, midZ, 1);
            ImageProcessor ip = imp.getProcessor();
            if (ip == null) continue;
            ChannelStats cs = new ChannelStats();
            cs.channel = c;
            cs.bitDepth = ip.getBitDepth();
            cs.ceiling = BitDepthUtil.saturationCeiling(ip);
            cs.saturationFrac = BitDepthUtil.saturatedFraction(ip, cs.ceiling);

            int[] hist = ip.getHistogram();
            ImageStatistics stats = ip.getStatistics();
            cs.utilisation = histUtilisation(hist, cs.ceiling);
            cs.snrDb = snrDb(hist);
            cs.pixelCV = stats.mean > 1.0 ? stats.stdDev / stats.mean : 0.0;

            // Tissue coverage: edge density via Sobel on a duplicate.
            ImageProcessor edges = ip.duplicate();
            edges.findEdges();
            cs.tissueCoverage = edgeDensity(edges);

            // Background uniformity: 4x4 tile CV of below-median medians.
            cs.bgUniformityCV = backgroundTileCV(ip);

            fs.perChannel.add(cs);
        }
        return fs;
    }

    private static double histUtilisation(int[] hist, int ceiling) {
        if (hist == null || hist.length == 0) return 0.0;
        int minIdx = -1, maxIdx = -1;
        for (int i = 0; i < hist.length; i++) {
            if (hist[i] >= 5) { minIdx = i; break; }
        }
        for (int i = hist.length - 1; i >= 0; i--) {
            if (hist[i] >= 5) { maxIdx = i; break; }
        }
        if (minIdx < 0 || maxIdx < 0) return 0.0;
        double denom = Math.max(1.0, ceiling > 0 ? (double) ceiling : (double) hist.length);
        return (double) (maxIdx - minIdx) / denom;
    }

    private static double snrDb(int[] hist) {
        if (hist == null || hist.length == 0) return 0.0;
        // Otsu split on the histogram
        int total = 0;
        for (int v : hist) total += v;
        if (total == 0) return 0.0;
        double sumAll = 0;
        for (int i = 0; i < hist.length; i++) sumAll += (double) i * hist[i];

        double sumB = 0;
        int wB = 0, wF;
        double varMax = -1;
        int threshold = 0;
        for (int i = 0; i < hist.length; i++) {
            wB += hist[i];
            if (wB == 0) continue;
            wF = total - wB;
            if (wF == 0) break;
            sumB += (double) i * hist[i];
            double mB = sumB / wB;
            double mF = (sumAll - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > varMax) {
                varMax = between;
                threshold = i;
            }
        }
        // Compute foreground mean and background mean+std.
        long fgCount = 0, bgCount = 0;
        double fgSum = 0;
        double bgSum = 0, bgSumSq = 0;
        for (int i = 0; i < hist.length; i++) {
            if (i >= threshold) {
                fgCount += hist[i];
                fgSum += (double) i * hist[i];
            } else {
                bgCount += hist[i];
                bgSum += (double) i * hist[i];
                bgSumSq += (double) i * i * hist[i];
            }
        }
        if (fgCount == 0 || bgCount == 0) return 0.0;
        double fgMean = fgSum / fgCount;
        double bgMean = bgSum / bgCount;
        double bgVar = Math.max(0.0, bgSumSq / bgCount - bgMean * bgMean);
        double bgStd = Math.sqrt(bgVar);
        if (bgStd < 1e-6) bgStd = 1.0;
        double snr = (fgMean - bgMean) / bgStd;
        if (snr <= 0) return 0.0;
        return 20.0 * Math.log10(snr);
    }

    private static double edgeDensity(ImageProcessor edges) {
        int[] hist = edges.getHistogram();
        long total = 0;
        for (int v : hist) total += v;
        if (total == 0) return 0.0;
        // Use the top 10% of edge magnitudes as "edge".
        long cut = (long) (total * 0.9);
        long seen = 0;
        int threshold = hist.length - 1;
        for (int i = 0; i < hist.length; i++) {
            seen += hist[i];
            if (seen >= cut) { threshold = i; break; }
        }
        long above = 0;
        for (int i = threshold; i < hist.length; i++) above += hist[i];
        return (double) above / (double) total;
    }

    private static double backgroundTileCV(ImageProcessor ip) {
        int w = ip.getWidth();
        int h = ip.getHeight();
        int tiles = 4;
        int tw = Math.max(1, w / tiles);
        int th = Math.max(1, h / tiles);

        ImageStatistics fullStats = ip.getStatistics();
        double globalThreshold = fullStats.mean;

        double[] medians = new double[tiles * tiles];
        int idx = 0;
        for (int ty = 0; ty < tiles; ty++) {
            for (int tx = 0; tx < tiles; tx++) {
                int x0 = tx * tw;
                int y0 = ty * th;
                int x1 = Math.min(w, x0 + tw);
                int y1 = Math.min(h, y0 + th);
                medians[idx++] = tileBelowThresholdMedian(ip, x0, y0, x1, y1, globalThreshold);
            }
        }
        double mean = 0;
        int n = 0;
        for (double m : medians) { if (m > 0) { mean += m; n++; } }
        if (n == 0) return 0.0;
        mean /= n;
        double var = 0;
        for (double m : medians) { if (m > 0) var += (m - mean) * (m - mean); }
        var /= n;
        double sd = Math.sqrt(var);
        return mean > 1e-6 ? sd / mean : 0.0;
    }

    private static double tileBelowThresholdMedian(ImageProcessor ip,
                                                    int x0, int y0, int x1, int y1,
                                                    double threshold) {
        List<Integer> values = new ArrayList<Integer>();
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int v = ip.getPixel(x, y);
                if (v < threshold) values.add(v);
            }
        }
        if (values.isEmpty()) return 0.0;
        java.util.Collections.sort(values);
        return values.get(values.size() / 2);
    }

    // ── Rendering ────────────────────────────────────

    private static void renderDynamicRange(List<FileStats> files, DiagnosticsReport.Section s) {
        for (FileStats fs : files) {
            StringBuilder sb = new StringBuilder();
            sb.append(fs.fileName).append(": ");
            for (ChannelStats cs : fs.perChannel) {
                sb.append("C").append(cs.channel).append(" ")
                  .append(pct(cs.utilisation)).append("% used, ")
                  .append(pct(cs.saturationFrac)).append("% clipped");
                if (cs.channel != fs.perChannel.get(fs.perChannel.size() - 1).channel) sb.append(" | ");
            }
            s.info(sb.toString());
            for (ChannelStats cs : fs.perChannel) {
                if (cs.saturationFrac > 0.01)
                    s.warn(fs.fileName + " C" + cs.channel + " is " + pct(cs.saturationFrac) + "% saturated");
                else if (cs.utilisation < 0.2 && cs.bitDepth > 8)
                    s.info(fs.fileName + " C" + cs.channel + " under-exposed (only " + pct(cs.utilisation) + "% of range used)");
            }
        }
    }

    private static void renderTissueCoverage(List<FileStats> files, DiagnosticsReport.Section s) {
        for (FileStats fs : files) {
            // Report first channel's coverage (usually DAPI) as a representative score
            if (fs.perChannel.isEmpty()) continue;
            ChannelStats cs = fs.perChannel.get(0);
            s.info(fs.fileName + ": tissue edge density " + pct(cs.tissueCoverage) + "%");
            if (cs.tissueCoverage < 0.05) s.warn(fs.fileName + " -- very low tissue coverage");
        }
    }

    private static void renderSnr(List<FileStats> files, DiagnosticsReport.Section s) {
        for (FileStats fs : files) {
            StringBuilder sb = new StringBuilder(fs.fileName).append(": ");
            for (int i = 0; i < fs.perChannel.size(); i++) {
                ChannelStats cs = fs.perChannel.get(i);
                if (i > 0) sb.append(" | ");
                sb.append("C").append(cs.channel).append(" ")
                  .append(String.format(Locale.ROOT, "%.1f dB", cs.snrDb));
            }
            s.info(sb.toString());
            for (ChannelStats cs : fs.perChannel) {
                if (cs.snrDb < 3.0)
                    s.warn(fs.fileName + " C" + cs.channel + " SNR "
                            + String.format(Locale.ROOT, "%.1f dB", cs.snrDb) + " -- unusable");
                else if (cs.snrDb < 10.0)
                    s.info(fs.fileName + " C" + cs.channel + " SNR "
                            + String.format(Locale.ROOT, "%.1f dB", cs.snrDb) + " -- marginal");
            }
        }
    }

    private static void renderUniformity(List<FileStats> files, DiagnosticsReport.Section s) {
        for (FileStats fs : files) {
            for (ChannelStats cs : fs.perChannel) {
                if (cs.bgUniformityCV > 0.15) {
                    s.warn(fs.fileName + " C" + cs.channel + " background CV "
                            + pct(cs.bgUniformityCV) + "% -- uneven illumination");
                }
            }
        }
    }

    private static void renderTooCleanTooNoisy(List<FileStats> files, DiagnosticsReport.Section s) {
        // Compute cohort CV per channel and flag outliers.
        int maxCh = 0;
        for (FileStats fs : files) maxCh = Math.max(maxCh, fs.perChannel.size());
        for (int ci = 0; ci < maxCh; ci++) {
            List<Double> cvs = new ArrayList<Double>();
            for (FileStats fs : files) {
                if (ci < fs.perChannel.size()) cvs.add(fs.perChannel.get(ci).pixelCV);
            }
            if (cvs.size() < 4) continue;
            double[] sorted = new double[cvs.size()];
            for (int i = 0; i < cvs.size(); i++) sorted[i] = cvs.get(i);
            Arrays.sort(sorted);
            double median = sorted[sorted.length / 2];
            double mad = 0;
            double[] dev = new double[sorted.length];
            for (int i = 0; i < sorted.length; i++) dev[i] = Math.abs(sorted[i] - median);
            Arrays.sort(dev);
            mad = dev[dev.length / 2] * 1.4826;
            if (mad < 1e-6) continue;
            for (FileStats fs : files) {
                if (ci >= fs.perChannel.size()) continue;
                double cv = fs.perChannel.get(ci).pixelCV;
                double z = Math.abs(cv - median) / mad;
                if (z > 3.0) {
                    s.warn(fs.fileName + " C" + (ci + 1) + ": pixel CV "
                            + pct(cv) + "% vs cohort " + pct(median) + "% (z=" + String.format(Locale.ROOT, "%.1f", z) + ")");
                }
            }
        }
    }

    private static void renderHistogramOutliers(List<FileStats> files, DiagnosticsReport.Section s) {
        // Simple: flag files where average utilisation is >2 MAD from cohort median.
        if (files.size() < 4) { s.info("Not enough files for outlier detection."); return; }
        double[] avgUtil = new double[files.size()];
        for (int i = 0; i < files.size(); i++) {
            double sum = 0; int n = 0;
            for (ChannelStats cs : files.get(i).perChannel) { sum += cs.utilisation; n++; }
            avgUtil[i] = n > 0 ? sum / n : 0;
        }
        double[] sorted = avgUtil.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        double[] dev = new double[sorted.length];
        for (int i = 0; i < sorted.length; i++) dev[i] = Math.abs(sorted[i] - median);
        Arrays.sort(dev);
        double mad = dev[dev.length / 2] * 1.4826;
        if (mad < 1e-6) { s.ok("No histogram outliers."); return; }
        int flagged = 0;
        for (int i = 0; i < files.size(); i++) {
            double z = Math.abs(avgUtil[i] - median) / mad;
            if (z > 3.0) {
                s.warn(files.get(i).fileName + " -- pixel distribution differs from cohort (z="
                        + String.format(Locale.ROOT, "%.1f", z) + ")");
                flagged++;
            }
        }
        if (flagged == 0) s.ok("No histogram outliers.");
    }

    private static String pct(double frac) {
        return String.format(Locale.ROOT, "%.1f", frac * 100.0);
    }
}
