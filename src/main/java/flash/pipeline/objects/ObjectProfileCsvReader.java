package flash.pipeline.objects;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the long-format per-object profile CSV written by {@link ObjectProfileCsvWriter} back into a
 * {@link ProfileAggregator}. Lets the Spatial Analysis consumer regenerate aggregate curves/figures
 * from saved data without recomputing voxel passes (Spatial has no raw intensity stacks in context).
 */
public final class ObjectProfileCsvReader {

    private ObjectProfileCsvReader() {}

    /** Parse {@code Per_Object_Profiles.csv}; returns an aggregator populated with the normalized curves. */
    public static ProfileAggregator aggregateFromPerObject(File file) throws IOException {
        ProfileAggregator agg = new ProfileAggregator();
        if (file == null || !file.isFile() || file.length() == 0L) return agg;

        // Buffer each object's curve: key = source|partner|type|group|label -> (bin -> valueNorm).
        Map<String, TreeMap<Integer, Double>> curves = new LinkedHashMap<String, TreeMap<Integer, Double>>();
        Map<String, String[]> keyMeta = new LinkedHashMap<String, String[]>(); // key -> {source,partner,type,group}

        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String headerLine = r.readLine();
            if (headerLine == null) return agg;
            Map<String, Integer> col = indexHeader(headerLine);
            Integer iAnimal = col.get("Animal"), iHemi = col.get("Hemisphere"), iRegion = col.get("Region"),
                    iRoi = col.get("ROI"), iSrc = col.get("SourceChannel"), iLabel = col.get("Label"),
                    iPartner = col.get("PartnerChannel"), iType = col.get("ProfileType"),
                    iBin = col.get("Bin"), iNorm = col.get("ValueNorm");
            if (iSrc == null || iPartner == null || iType == null || iBin == null || iNorm == null) return agg;

            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                List<String> f = splitCsv(line);
                if (f.size() <= max(iSrc, iPartner, iType, iBin, iNorm)) continue;
                String group = ObjectProfileCsvWriter.groupKey(
                        get(f, iAnimal), get(f, iHemi), get(f, iRegion), get(f, iRoi));
                String source = get(f, iSrc), partner = get(f, iPartner), type = get(f, iType);
                String label = get(f, iLabel);
                int bin;
                double valueNorm;
                try {
                    bin = Integer.parseInt(get(f, iBin).trim());
                    String nv = get(f, iNorm).trim();
                    valueNorm = nv.isEmpty() ? Double.NaN : Double.parseDouble(nv);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                String key = source + "<|>" + partner + "<|>" + type + "<|>" + group + "<|>" + label;
                TreeMap<Integer, Double> curve = curves.get(key);
                if (curve == null) {
                    curve = new TreeMap<Integer, Double>();
                    curves.put(key, curve);
                    keyMeta.put(key, new String[] {source, partner, type, group});
                }
                curve.put(bin, valueNorm);
            }
        } finally {
            r.close();
        }

        for (Map.Entry<String, TreeMap<Integer, Double>> e : curves.entrySet()) {
            TreeMap<Integer, Double> curve = e.getValue();
            if (curve.isEmpty()) continue;
            int len = curve.lastKey() + 1;
            double[] arr = new double[len];
            for (int i = 0; i < len; i++) {
                Double v = curve.get(i);
                arr[i] = v != null ? v : Double.NaN;
            }
            String[] m = keyMeta.get(e.getKey());
            agg.add(m[0], m[1], m[2], m[3], arr);
        }
        return agg;
    }

    private static Map<String, Integer> indexHeader(String header) {
        Map<String, Integer> idx = new LinkedHashMap<String, Integer>();
        List<String> cols = splitCsv(header);
        for (int i = 0; i < cols.size(); i++) idx.put(cols.get(i).trim(), i);
        return idx;
    }

    private static int max(int... v) {
        int m = Integer.MIN_VALUE;
        for (int x : v) if (x > m) m = x;
        return m;
    }

    private static String get(List<String> f, Integer i) {
        return i != null && i >= 0 && i < f.size() ? f.get(i) : "";
    }

    /**
     * Minimal RFC-4180-ish split handling double-quoted fields with escaped quotes. Reads one
     * physical line per record: safe here because every field is numeric or a filename-derived
     * token (animal / hemisphere / region / ROI / channel / profile-type) that cannot contain a
     * newline. Embedded newlines are not supported by design.
     */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else inQuotes = false;
                } else sb.append(c);
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(sb.toString()); sb.setLength(0);
            } else sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }
}
