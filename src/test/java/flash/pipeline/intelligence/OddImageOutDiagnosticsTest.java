package flash.pipeline.intelligence;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class OddImageOutDiagnosticsTest {

    @Test
    public void render_flagsSavedMetricOutlierUsingIqrFallbackWhenMadIsZero() {
        SummaryHistoryStore.Snapshot snapshot = new SummaryHistoryStore.Snapshot();
        snapshot.imageMetadata.put("img1", image("Animal 4", "SCN", 1, "Animal4_section1.tif", 10));
        snapshot.imageMetadata.put("img2", image("Animal 4", "SCN", 2, "Animal4_section2.tif", 10));
        snapshot.imageMetadata.put("img3", image("Animal 4", "SCN", 3, "Animal4_section3.tif", 10));
        snapshot.imageMetadata.put("img4", image("Animal 4", "SCN", 4, "Animal4_section4.tif", 1000));

        DiagnosticsReport.Section section = new DiagnosticsReport("x").addSection("Odd image out");
        OddImageOutDiagnostics.render(snapshot, section);

        assertTrue(hasFinding(section, DiagnosticsReport.Severity.WARN, "Animal 4 section 4"));
        assertTrue(hasFinding(section, DiagnosticsReport.Severity.WARN, "1000 DAPI object count"));
        assertTrue(hasFinding(section, DiagnosticsReport.Severity.WARN, "IQR"));
    }

    private static Map<String, Object> image(String animalId,
                                             String region,
                                             int sectionIndex,
                                             String displayName,
                                             double metricValue) {
        LinkedHashMap<String, Object> image = new LinkedHashMap<String, Object>();
        image.put("animalId", animalId);
        image.put("region", region);
        image.put("sectionIndex", sectionIndex);
        image.put("displayName", displayName);

        LinkedHashMap<String, Object> metrics = new LinkedHashMap<String, Object>();
        metrics.put("DAPI object count", metricValue);
        image.put("metrics", metrics);
        return image;
    }

    private static boolean hasFinding(DiagnosticsReport.Section section,
                                      DiagnosticsReport.Severity severity,
                                      String snippet) {
        for (DiagnosticsReport.Finding finding : section.findings) {
            if (finding.severity == severity && finding.message.contains(snippet)) return true;
        }
        return false;
    }
}
