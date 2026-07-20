package flash.pipeline.objects;

import flash.pipeline.io.CsvSupport;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes the full Object Intensity Profiling curves to {@code FLASH/Results/Tables/Object Intensity
 * Profiling/}: a long-format per-object file (one row per object × partner × profile-type × bin) and
 * an aggregated mean±SEM file. The compact per-object scalar descriptors are written separately into
 * the main per-object table (producer hook) so they flow through aggregation.
 *
 * <p>Pure IO. Designed to be called single-threaded at the end of an analysis run (after the parallel
 * per-image pass), appending one image at a time, so no locking is needed.
 */
public final class ObjectProfileCsvWriter {

    public static final String PER_OBJECT_FILE = "Per_Object_Profiles.csv";
    public static final String AGGREGATE_FILE = "Aggregate_Profiles.csv";
    /** Persisted producer intent so the Spatial consumer regenerates figures only when they were on. */
    public static final String FIGURES_FLAG_FILE = "figures_enabled.flag";

    private static final String PER_OBJECT_HEADER =
            "Animal,Hemisphere,Region,ROI,SourceChannel,Label,VoxelCount,PartnerChannel,ProfileType,Bin,AxisNorm,ValueRaw,ValueNorm";
    private static final String AGGREGATE_HEADER =
            "SourceChannel,PartnerChannel,ProfileType,Group,Bin,AxisNorm,Mean,SEM,N";

    private ObjectProfileCsvWriter() {}

    /**
     * Aggregation/group key for one image's profiles — the same identity used to match per-object
     * rows (animal + hemisphere + region + ROI), so bilateral or multi-ROI runs are not collapsed.
     * The producer and the Spatial-consumer reader must build this identically.
     */
    public static String groupKey(String animal, String hemisphere, String region, String roi) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, animal);
        appendPart(sb, hemisphere);
        appendPart(sb, region);
        appendPart(sb, roi);
        return sb.length() == 0 ? "(all)" : sb.toString();
    }

    private static void appendPart(StringBuilder sb, String p) {
        if (p == null || p.isEmpty()) return;
        if (sb.length() > 0) sb.append(" | ");
        sb.append(p);
    }

    /** Record whether the producer generated figures, so a later Spatial refresh can match the intent. */
    public static void writeFiguresIntent(File dir, boolean enabled) {
        if (dir == null) return;
        dir.mkdirs();
        try {
            PrintWriter w = open(new File(dir, FIGURES_FLAG_FILE), false);
            try {
                w.println(enabled ? "true" : "false");
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
            // Best-effort marker; the consumer falls back to its default when it is absent.
        }
    }

    /** Read the persisted figure intent; {@code defaultEnabled} when the marker is missing/unreadable. */
    public static boolean readFiguresIntent(File dir, boolean defaultEnabled) {
        if (dir == null) return defaultEnabled;
        File f = new File(dir, FIGURES_FLAG_FILE);
        if (!f.isFile()) return defaultEnabled;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String line = r.readLine();
                return line == null ? defaultEnabled : line.trim().equalsIgnoreCase("true");
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return defaultEnabled;
        }
    }

    /** Append one image's per-object curves to the long-format file (writes the header if new). */
    public static void appendPerObject(File dir, String animal, String hemisphere, String region,
                                       String roi, List<ObjectProfileResult> results) throws IOException {
        if (results == null || results.isEmpty()) return;
        if (dir != null) dir.mkdirs();
        File file = new File(dir, PER_OBJECT_FILE);
        boolean writeHeader = !file.isFile() || file.length() == 0L;
        PrintWriter w = open(file, true);
        try {
            if (writeHeader) w.println(PER_OBJECT_HEADER);
            for (ObjectProfileResult r : results) {
                for (ObjectProfileResult.PartnerProfiles pf : r.byPartner.values()) {
                    writeCurve(w, animal, hemisphere, region, roi, r, pf, ProfileAggregator.RADIAL, pf.radialRaw, pf.radialNorm);
                    writeCurve(w, animal, hemisphere, region, roi, r, pf, ProfileAggregator.MARGINAL_X, pf.marginalXRaw, pf.marginalXNorm);
                    writeCurve(w, animal, hemisphere, region, roi, r, pf, ProfileAggregator.MARGINAL_Y, pf.marginalYRaw, pf.marginalYNorm);
                    writeCurve(w, animal, hemisphere, region, roi, r, pf, ProfileAggregator.MARGINAL_Z, pf.marginalZRaw, pf.marginalZNorm);
                    writeCurve(w, animal, hemisphere, region, roi, r, pf, ProfileAggregator.PC_MAJOR, pf.pcMajorRaw, pf.pcMajorNorm);
                    writeCurve(w, animal, hemisphere, region, roi, r, pf, ProfileAggregator.PC_MINOR, pf.pcMinorRaw, pf.pcMinorNorm);
                    writeCurve(w, animal, hemisphere, region, roi, r, pf, ProfileAggregator.PC_THIRD, pf.pcThirdRaw, pf.pcThirdNorm);
                    writeCurve(w, animal, hemisphere, region, roi, r, pf, ProfileAggregator.ANGULAR, pf.angularRaw, pf.angularNorm);
                }
            }
        } finally {
            w.close();
        }
    }

    /** Write (overwrite) the aggregated mean±SEM file from a populated aggregator. */
    public static void writeAggregated(File dir, ProfileAggregator agg) throws IOException {
        if (agg == null) return;
        List<ProfileAggregator.AggregatedProfile> aggs = agg.results();
        if (aggs.isEmpty()) return;
        if (dir != null) dir.mkdirs();
        PrintWriter w = open(new File(dir, AGGREGATE_FILE), false);
        try {
            w.println(AGGREGATE_HEADER);
            for (ProfileAggregator.AggregatedProfile a : aggs) {
                for (int i = 0; i < a.mean.length; i++) {
                    w.println(CsvSupport.escapeField(a.source) + ","
                            + CsvSupport.escapeField(a.partner) + ","
                            + CsvSupport.escapeField(a.profileType) + ","
                            + CsvSupport.escapeField(a.groupKey)
                            + "," + i + "," + num(a.x[i]) + "," + num(a.mean[i]) + "," + num(a.sem[i]) + "," + a.n[i]);
                }
            }
        } finally {
            w.close();
        }
    }

    private static void writeCurve(PrintWriter w, String animal, String hemisphere, String region,
                                   String roi, ObjectProfileResult r,
                                   ObjectProfileResult.PartnerProfiles pf, String type,
                                   double[] raw, double[] norm) {
        if (raw == null) return;
        for (int i = 0; i < raw.length; i++) {
            double axis = ProfileAggregator.axisAt(type, i, raw.length);
            double nv = norm != null && i < norm.length ? norm[i] : Double.NaN;
            w.println(CsvSupport.escapeField(animal) + ","
                    + CsvSupport.escapeField(hemisphere) + ","
                    + CsvSupport.escapeField(region) + ","
                    + CsvSupport.escapeField(roi) + ","
                    + CsvSupport.escapeField(r.sourceChannel) + "," + r.label + ","
                    + r.voxelCount + "," + CsvSupport.escapeField(pf.partnerChannel) + ","
                    + CsvSupport.escapeField(type) + "," + i + ","
                    + num(axis) + "," + num(raw[i]) + "," + num(nv));
        }
    }

    private static PrintWriter open(File file, boolean append) throws IOException {
        return new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(file, append), StandardCharsets.UTF_8));
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "" : Double.toString(v);
    }

}
