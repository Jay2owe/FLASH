package flash.pipeline.intelligence;

import java.io.File;
import java.util.List;

/**
 * Orchestrates the "Check my data" diagnostic suite for a given directory.
 * Fast checks (filesystem, metadata) run unconditionally; heavier checks
 * that open pixel data are gated behind a flag so the report can be produced
 * quickly on large cohorts.
 */
public final class DiagnosticsRunner {

    public static final class Options {
        public boolean runCohortIntegrity = true;
        public boolean runMetadataChecks  = true;
        public boolean runImageQuality    = false;   // opens images, slower
    }

    private DiagnosticsRunner() {}

    public static DiagnosticsReport run(String directory, Options opts) {
        if (opts == null) opts = new Options();
        DiagnosticsReport report = new DiagnosticsReport(directory);

        // Header section — always first
        DiagnosticsReport.Section header = report.addSection("Scan");
        if (directory == null || !new File(directory).isDirectory()) {
            header.error("Directory not found: " + directory);
            return report;
        }
        File[] files = JunkFileFilter.listCleanChildren(new File(directory));
        int fileCount = files.length;
        header.info("Folder: " + directory);
        header.info("Entries: " + fileCount);

        // ── Cohort integrity (filesystem only) ──
        if (opts.runCohortIntegrity) {
            DiagnosticsReport.Section dups = report.addSection("Duplicate files");
            CohortIntegrity.checkDuplicates(directory, dups);

            DiagnosticsReport.Section naming = report.addSection("Region-name drift");
            CohortIntegrity.checkNamingDrift(directory, naming);

            DiagnosticsReport.Section rois = report.addSection("ROI zips");
            CohortIntegrity.checkRoiZips(directory, rois);
        }

        // ── Metadata checks (fast: no pixels) ──
        if (opts.runMetadataChecks) {
            DiagnosticsReport.Section scanSec = report.addSection("Metadata scan");
            scanSec.info("Reading headers…");
            List<MetadataDiagnostics.SeriesInfo> series =
                    MetadataDiagnostics.scanDirectory(directory);
            scanSec.info("Parsed metadata for " + series.size() + " series.");

            DiagnosticsReport.Section obj = report.addSection("Objective");
            MetadataDiagnostics.checkObjective(series, obj);

            DiagnosticsReport.Section instrument = report.addSection("Instrument fingerprint (P-02)");
            MetadataDiagnostics.checkInstrumentFingerprint(series, instrument);

            DiagnosticsReport.Section dates = report.addSection("Acquisition dates");
            MetadataDiagnostics.checkAcquisitionDates(series, dates);

            DiagnosticsReport.Section zdepth = report.addSection("Z-stack depth");
            MetadataDiagnostics.checkZDepth(series, zdepth);

            DiagnosticsReport.Section fmt = report.addSection("Format & channels");
            MetadataDiagnostics.checkFormatHomogeneity(series, fmt);

            DiagnosticsReport.Section scope = report.addSection("Microscope class (L-02)");
            MetadataDiagnostics.checkScopeClass(series, scope);

            DiagnosticsReport.Section nyq = report.addSection("Pixel-size vs NA (L-03)");
            MetadataDiagnostics.checkNyquist(series, nyq);
        }

        // ── Image quality (opens pixels, slower; gated) ──
        DiagnosticsReport.Section oddImageOut = report.addSection("Outlier detection - odd image out (I-04)");
        OddImageOutDiagnostics.check(directory, oddImageOut);

        if (opts.runImageQuality) {
            ImageQualityDiagnostics.run(directory, report);
        }

        return report;
    }
}
