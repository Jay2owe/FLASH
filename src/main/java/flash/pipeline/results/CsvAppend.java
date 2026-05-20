package flash.pipeline.results;

import flash.pipeline.bin.BinConfigIO;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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

        // keep dest header as-is; append src rows after header
        List<String> out = new ArrayList<>(dest);
        for (int i = 1; i < src.size(); i++) {
            out.add(src.get(i));
        }
        BinConfigIO.writeAtomic(destCsv.toPath(), out);
    }
}
