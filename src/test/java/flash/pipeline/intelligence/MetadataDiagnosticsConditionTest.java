package flash.pipeline.intelligence;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * "Check my data" condition health is derived from the canonical Conditions.csv
 * (and the animals that actually appear in result tables), not filename
 * inference alone.
 */
public class MetadataDiagnosticsConditionTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void singleConditionWarnsAndExplainsProjectSetup() throws Exception {
        File root = temp.newFolder("diag-single");
        writeObjectAnimals(root, "Mouse1", "Mouse2");
        saveConditions(root, "Mouse1", "Control", "Mouse2", "Control");

        DiagnosticsReport.Section section = run(root, null);

        assertTrue(hasSeverity(section, DiagnosticsReport.Severity.WARN));
        assertTrue(messagesContain(section, "Edit project setup"));
        assertTrue(messagesContain(section, "Review conditions"));
    }

    @Test
    public void twoExplicitConditionsReportOk() throws Exception {
        File root = temp.newFolder("diag-two");
        writeObjectAnimals(root, "Mouse1", "Mouse2");
        saveConditions(root, "Mouse1", "Control", "Mouse2", "Treated");

        DiagnosticsReport.Section section = run(root, null);

        assertFalse(hasSeverity(section, DiagnosticsReport.Severity.WARN));
        assertTrue(hasSeverity(section, DiagnosticsReport.Severity.OK));
    }

    @Test
    public void noAnimalsReportsInfoOnly() throws Exception {
        File root = temp.newFolder("diag-empty");

        DiagnosticsReport.Section section = run(root, null);

        assertTrue(messagesContain(section, "No animals found"));
        assertFalse(hasSeverity(section, DiagnosticsReport.Severity.WARN));
    }

    @Test
    public void fallsBackToSeriesAnimalsWhenNoResultTables() throws Exception {
        File root = temp.newFolder("diag-series");
        List<MetadataDiagnostics.SeriesInfo> series = new ArrayList<MetadataDiagnostics.SeriesInfo>();
        series.add(seriesInfo("Study.lif - Mouse1_LH_SCN"));
        series.add(seriesInfo("Study.lif - Mouse2_LH_SCN"));

        DiagnosticsReport.Section section = run(root, series);

        assertFalse(messagesContain(section, "No animals found"));
        assertTrue(messagesContain(section, "image names"));
    }

    // --- helpers -----------------------------------------------------------

    private static DiagnosticsReport.Section run(File root, List<MetadataDiagnostics.SeriesInfo> series) {
        DiagnosticsReport.Section section = new DiagnosticsReport.Section("Conditions");
        MetadataDiagnostics.checkConditions(root.getAbsolutePath(), series, section);
        return section;
    }

    private static MetadataDiagnostics.SeriesInfo seriesInfo(String imageName) {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.imageName = imageName;
        info.file = "Study.lif";
        return info;
    }

    private static void writeObjectAnimals(File root, String... animals) throws Exception {
        File dir = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(dir.mkdirs());
        StringBuilder sb = new StringBuilder("Animal Name,Count\n");
        for (String animal : animals) {
            sb.append(animal).append(",1\n");
        }
        PrintWriter pw = new PrintWriter(new File(dir, "CK1D.csv"), "UTF-8");
        try {
            pw.print(sb.toString());
        } finally {
            pw.close();
        }
    }

    private static void saveConditions(File root, String... animalCondition) throws Exception {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < animalCondition.length; i += 2) {
            map.put(animalCondition[i], animalCondition[i + 1]);
        }
        ConditionManifestIO.saveAssignments(root.getAbsolutePath(), map);
    }

    private static boolean hasSeverity(DiagnosticsReport.Section section, DiagnosticsReport.Severity severity) {
        for (DiagnosticsReport.Finding finding : section.findings) {
            if (finding.severity == severity) return true;
        }
        return false;
    }

    private static boolean messagesContain(DiagnosticsReport.Section section, String needle) {
        for (DiagnosticsReport.Finding finding : section.findings) {
            if (finding.message != null && finding.message.contains(needle)) return true;
        }
        return false;
    }
}
