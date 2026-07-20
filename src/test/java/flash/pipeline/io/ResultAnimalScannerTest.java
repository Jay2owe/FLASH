package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ResultAnimalScannerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void collectsAnimalsFromObjectResultCsvs() throws Exception {
        File root = temp.newFolder("project");
        File objects = layout(root).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "Mouse01_LH_SCN.csv"),
                "Animal Name,DAPI_Count\nMouse01,10\nMouse01,12\n");
        writeCsv(new File(objects, "Mouse02_RH_SCN.csv"),
                "Animal Name,DAPI_Count\nMouse02,8\n");

        assertEquals(set("Mouse01", "Mouse02"),
                ResultAnimalScanner.collect(root.getAbsolutePath()));
    }

    @Test
    public void skipsDetailsAndTempFiles() throws Exception {
        File root = temp.newFolder("project");
        File objects = layout(root).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        writeCsv(new File(objects, "analysis details.csv"), "Animal Name\nGhost\n");
        writeCsv(new File(objects, "temp_scratch.csv"), "Animal Name\nTempGhost\n");
        writeCsv(new File(objects, "Mouse01.csv"), "Animal Name\nMouse01\n");

        assertEquals(set("Mouse01"),
                ResultAnimalScanner.collect(root.getAbsolutePath()));
    }

    @Test
    public void fallsBackToConditionManifestKeysWhenNoResults() throws Exception {
        File root = temp.newFolder("project");
        LinkedHashMap<String, String> saved = new LinkedHashMap<String, String>();
        saved.put("Mouse01", "Control");
        saved.put("Mouse02", "Treated");
        ConditionManifestIO.saveAssignments(root.getAbsolutePath(), saved);

        assertEquals(set("Mouse01", "Mouse02"),
                ResultAnimalScanner.collect(root.getAbsolutePath()));
    }

    @Test
    public void emptyWhenNothingExists() throws Exception {
        File root = temp.newFolder("project");
        assertTrue(ResultAnimalScanner.collect(root.getAbsolutePath()).isEmpty());
    }

    private static FlashProjectLayout layout(File root) {
        return FlashProjectLayout.forDirectory(root.getAbsolutePath());
    }

    private static LinkedHashSet<String> set(String... names) {
        return names.length == 1
                ? new LinkedHashSet<String>(Collections.singletonList(names[0]))
                : new LinkedHashSet<String>(Arrays.asList(names));
    }

    private static void writeCsv(File file, String content) throws Exception {
        PrintWriter pw = new PrintWriter(file, "UTF-8");
        try {
            pw.print(content);
        } finally {
            pw.close();
        }
    }
}
