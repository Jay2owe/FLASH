package flash.pipeline.objects;

import ij.ImagePlus;
import ij.gui.Plot;
import ij.io.FileSaver;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders aggregate Object Intensity Profiling figures (one per source channel × group × profile
 * type) by overlaying each partner channel's mean curve. Headless-safe: builds {@link Plot} images
 * offscreen and saves PNGs via {@link FileSaver} without ever showing a window.
 *
 * <p>Aggregate only — no per-object figures (avoids the figure explosion for thousands of objects).
 */
public final class ObjectProfileFigureWriter {

    private static final Color[] FALLBACK = {
            new Color(0x1f77b4), new Color(0xd62728), new Color(0x2ca02c), new Color(0x9467bd),
            new Color(0xff7f0e), new Color(0x17becf), new Color(0x8c564b), new Color(0xbcbd22)
    };

    private ObjectProfileFigureWriter() {}

    /**
     * @param dir           output directory (created if needed)
     * @param agg           populated aggregator
     * @param channelColors partner channel → display colour (may be null/partial; a palette fills gaps)
     */
    public static void writeFigures(File dir, ProfileAggregator agg, Map<String, Color> channelColors) {
        if (dir != null) dir.mkdirs();
        // Clear stale figures first — even when there is nothing to draw (null/empty aggregate, e.g.
        // a scalar-only run) — so PNGs from a previous run with different groups/profile types never
        // linger. The full set is always regenerated from the current aggregate below.
        clearFigures(dir);
        if (agg == null) return;
        List<ProfileAggregator.AggregatedProfile> all = agg.results();
        if (all.isEmpty()) return;

        // Group partners under each (source, group, profileType).
        Map<String, List<ProfileAggregator.AggregatedProfile>> byPanel =
                new LinkedHashMap<String, List<ProfileAggregator.AggregatedProfile>>();
        for (ProfileAggregator.AggregatedProfile a : all) {
            String key = a.source + "|" + a.groupKey + "|" + a.profileType;
            List<ProfileAggregator.AggregatedProfile> list = byPanel.get(key);
            if (list == null) { list = new ArrayList<ProfileAggregator.AggregatedProfile>(); byPanel.put(key, list); }
            list.add(a);
        }

        int colorIdx = 0;
        Map<String, Color> resolved = new LinkedHashMap<String, Color>();
        for (Map.Entry<String, List<ProfileAggregator.AggregatedProfile>> e : byPanel.entrySet()) {
            List<ProfileAggregator.AggregatedProfile> partners = e.getValue();
            if (partners.isEmpty()) continue;
            ProfileAggregator.AggregatedProfile first = partners.get(0);
            String type = first.profileType;
            String xLabel = ProfileAggregator.RADIAL.equals(type) ? "normalised radius (0=centre)"
                    : ProfileAggregator.ANGULAR.equals(type) ? "angle (deg)"
                    : "normalised position (centre=0)";
            Plot plot = new Plot("OIP " + type, xLabel, "mean intensity (normalised)");
            double[] lim = limits(partners);
            plot.setLimits(lim[0], lim[1], lim[2], lim[3]);

            for (ProfileAggregator.AggregatedProfile a : partners) {
                Color c = colorFor(a.partner, channelColors, resolved);
                colorIdx++;
                plot.setColor(c);
                // Pass means as-is: ij.gui.Plot leaves gaps for NaN bins rather than drawing a
                // straight segment through missing data (limits are set manually above, so NaN does
                // not affect auto-ranging).
                plot.addPoints(a.x, a.mean, Plot.LINE);
                plot.setColor(c);
                plot.addLabel(0.02, 0.05 * (partners.indexOf(a) + 1), a.partner);
            }
            plot.setColor(Color.BLACK);

            ImagePlus imp = plot.getImagePlus();
            String fname = sanitize(first.source + "__" + first.groupKey + "__" + type) + ".png";
            new FileSaver(imp).saveAsPng(new File(dir, fname).getAbsolutePath());
        }
    }

    private static Color colorFor(String partner, Map<String, Color> provided, Map<String, Color> resolved) {
        if (provided != null && provided.get(partner) != null) return provided.get(partner);
        Color c = resolved.get(partner);
        if (c == null) { c = FALLBACK[resolved.size() % FALLBACK.length]; resolved.put(partner, c); }
        return c;
    }

    /** {xMin,xMax,yMin,yMax} ignoring NaN; pads y a little. */
    private static double[] limits(List<ProfileAggregator.AggregatedProfile> partners) {
        double xmin = Double.POSITIVE_INFINITY, xmax = Double.NEGATIVE_INFINITY;
        double ymin = Double.POSITIVE_INFINITY, ymax = Double.NEGATIVE_INFINITY;
        for (ProfileAggregator.AggregatedProfile a : partners) {
            for (double v : a.x) if (!Double.isNaN(v)) { xmin = Math.min(xmin, v); xmax = Math.max(xmax, v); }
            for (double v : a.mean) if (!Double.isNaN(v)) { ymin = Math.min(ymin, v); ymax = Math.max(ymax, v); }
        }
        if (!(xmax > xmin)) { xmin = 0; xmax = 1; }
        if (!(ymax > ymin)) { ymin = 0; ymax = 1; }
        double pad = 0.05 * (ymax - ymin);
        return new double[] {xmin, xmax, ymin - pad, ymax + pad};
    }

    /** Delete previously-written OIP figures (PNG/SVG) so a regenerated set leaves no stale files. */
    public static void clearFigures(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isFile() && (n.endsWith(".png") || n.endsWith(".svg"))) {
                f.delete();
            }
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
