package flash.pipeline.io;

import flash.pipeline.naming.OrientationManifestRow;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists optional hemisphere aliases used only by Image Orientation Setup.
 */
public final class OrientationAliasIO {

    public static final String FILE_NAME = FlashProjectLayout.ORIENTATION_ALIASES_FILENAME;

    private OrientationAliasIO() {}

    public static File getFile(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        // TODO(results-folder-layout-plan stage 09): move this to tablesProjectSummaryWriteDir().
        return new File(layout.tablesRoiWriteDir(), FILE_NAME);
    }

    public static File getExistingFile(String directory) {
        File candidate = getFile(directory);
        return candidate.isFile() ? candidate : null;
    }

    public static LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>> readIfExists(String directory) {
        File file = getExistingFile(directory);
        LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>> aliases = emptyMap();
        if (file == null || !file.isFile()) return aliases;

        try {
            CsvSupport.RecordReader reader = CsvSupport.openRecordReader(file);
            try {
                CsvSupport.Record header = reader.readRecord();
                if (header == null) return aliases;
                CsvSupport.Record record;
                while ((record = reader.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String[] fields = CsvSupport.parseRecord(record.text);
                    if (fields.length < 2) continue;
                    OrientationManifestRow.Hemisphere hemisphere =
                            OrientationManifestRow.Hemisphere.fromCsv(fields[0]);
                    if (hemisphere == OrientationManifestRow.Hemisphere.UNKNOWN) continue;
                    String alias = fields[1] == null ? "" : fields[1].trim();
                    if (!alias.isEmpty()) aliases.get(hemisphere).add(alias);
                }
            } finally {
                reader.close();
            }
        } catch (IOException ignored) {
            return emptyMap();
        }
        return aliases;
    }

    public static void save(String directory,
                            Map<OrientationManifestRow.Hemisphere, List<String>> aliases) throws IOException {
        File file = getFile(directory);
        File exportDir = file.getParentFile();
        if (exportDir != null && !exportDir.isDirectory()) {
            IoUtils.mustMkdirs(exportDir);
        }

        CsvSupport.writeAtomically(file, new CsvSupport.WriterAction() {
            @Override
            public void write(PrintWriter pw) {
                pw.println(CsvSupport.joinRow(Arrays.asList("Hemisphere", "Alias")));
                if (aliases == null) return;
                writeAliases(pw, OrientationManifestRow.Hemisphere.LH, aliases.get(OrientationManifestRow.Hemisphere.LH));
                writeAliases(pw, OrientationManifestRow.Hemisphere.RH, aliases.get(OrientationManifestRow.Hemisphere.RH));
            }
        });
    }

    private static void writeAliases(PrintWriter pw,
                                     OrientationManifestRow.Hemisphere hemisphere,
                                     List<String> aliases) {
        if (aliases == null) return;
        for (String alias : aliases) {
            String trimmed = alias == null ? "" : alias.trim();
            if (trimmed.isEmpty()) continue;
            pw.println(CsvSupport.joinRow(Arrays.asList(hemisphere.toCsv(), trimmed)));
        }
    }

    private static LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>> emptyMap() {
        LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>> aliases =
                new LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>>();
        aliases.put(OrientationManifestRow.Hemisphere.LH, new ArrayList<String>());
        aliases.put(OrientationManifestRow.Hemisphere.RH, new ArrayList<String>());
        return aliases;
    }
}
