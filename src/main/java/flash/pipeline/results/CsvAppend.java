package flash.pipeline.results;

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
            Files.copy(srcCsv.toPath(), destCsv.toPath());
            return;
        }

        List<String> dest = Files.readAllLines(destCsv.toPath(), StandardCharsets.UTF_8);
        List<String> src = Files.readAllLines(srcCsv.toPath(), StandardCharsets.UTF_8);
        if (src.isEmpty()) return;

        String header = src.get(0);
        // keep dest header as-is; append src rows after header
        List<String> out = new ArrayList<>(dest);
        for (int i = 1; i < src.size(); i++) {
            out.add(src.get(i));
        }
        Files.write(destCsv.toPath(), out, StandardCharsets.UTF_8);
    }
}
