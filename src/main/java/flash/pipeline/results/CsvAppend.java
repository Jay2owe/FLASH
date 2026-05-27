package flash.pipeline.results;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.io.CsvSupport;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Simple CSV append helper for macro-like "extendTable" behavior. */
public final class CsvAppend {

    private CsvAppend() {}

    /**
     * Appends rows from srcCsv to destCsv. Assumes both share the same header line.
     * If dest does not exist, it is created by copying src.
     */
    public static void append(File destCsv, File srcCsv) throws Exception {
        if (destCsv == null || srcCsv == null) return;
        if (!srcCsv.exists()) return;

        if (!destCsv.exists()) {
            List<String> src = Files.readAllLines(srcCsv.toPath(), StandardCharsets.UTF_8);
            BinConfigIO.writeAtomic(destCsv.toPath(), src);
            return;
        }

        List<String> dest = Files.readAllLines(destCsv.toPath(), StandardCharsets.UTF_8);
        List<String> src = Files.readAllLines(srcCsv.toPath(), StandardCharsets.UTF_8);
        if (src.isEmpty()) return;
        if (dest.isEmpty()) {
            BinConfigIO.writeAtomic(destCsv.toPath(), src);
            return;
        }

        String[] destHeader = CsvSupport.parseRecord(dest.get(0));
        String[] srcHeader = CsvSupport.parseRecord(src.get(0));
        if (!headersMatch(destHeader, srcHeader)) {
            throw new IOException("CSV headers do not match: "
                    + destCsv.getName() + " has " + Arrays.toString(destHeader)
                    + ", " + srcCsv.getName() + " has " + Arrays.toString(srcHeader));
        }

        // keep dest header as-is; append src rows after header
        List<String> out = new ArrayList<>(dest);
        for (int i = 1; i < src.size(); i++) {
            out.add(src.get(i));
        }
        BinConfigIO.writeAtomic(destCsv.toPath(), out);
    }

    private static boolean headersMatch(String[] destHeader, String[] srcHeader) {
        if (destHeader == null || srcHeader == null || destHeader.length != srcHeader.length) {
            return false;
        }
        for (int i = 0; i < destHeader.length; i++) {
            String left = destHeader[i] == null ? "" : destHeader[i].trim();
            String right = srcHeader[i] == null ? "" : srcHeader[i].trim();
            if (!left.equals(right)) {
                return false;
            }
        }
        return true;
    }
}
