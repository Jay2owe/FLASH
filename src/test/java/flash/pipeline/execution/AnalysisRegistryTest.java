package flash.pipeline.execution;

import flash.pipeline.FLASH_Pipeline;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AnalysisRegistryTest {

    @Test
    public void everyRegisteredIndexHasStableKeyLabelAndCliFlag() {
        for (int i = 0; i <= FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION; i++) {
            AnalysisRegistry entry = AnalysisRegistry.forIndex(i);
            assertNotNull("missing registry entry for index " + i, entry);
            assertFalse(entry.analysisKey().trim().isEmpty());
            assertFalse(entry.label().trim().isEmpty());
            assertFalse(entry.cliFlag().trim().isEmpty());
            assertEquals(entry, AnalysisRegistry.forKey(entry.analysisKey()));
        }
    }

    @Test
    public void registryDocumentsRuntimeGatedAndHiddenEntries() {
        AnalysisRegistry excel = AnalysisRegistry.forIndex(FLASH_Pipeline.IDX_EXCEL_EXPORT);
        assertEquals("ExcelSummaryExportAnalysis", excel.analysisKey());
        assertFalse("Excel class must stay runtime-gated here", excel.hasConcreteClass());
        assertTrue(excel.note().contains("runtime-gated"));

        AnalysisRegistry lineDistance = AnalysisRegistry.forIndex(FLASH_Pipeline.IDX_LINE_DISTANCE);
        assertTrue(lineDistance.note().contains("hidden"));

        List<String> docs = AnalysisRegistry.documentedSpecialEntries();
        assertTrue(join(docs).contains("ExcelSummaryExportAnalysis"));
        assertTrue(join(docs).contains("LineDistanceAnalysis"));
        assertTrue(join(docs).contains("ImageOrientationSetupAnalysis"));
    }

    @Test
    public void noOrientationEntryIsRegistered() {
        assertNull(AnalysisRegistry.forKey("ImageOrientationSetupAnalysis"));
    }

    @Test
    public void registeredIndexesAreUnique() {
        Set<Integer> indexes = new HashSet<Integer>();
        for (AnalysisRegistry entry : AnalysisRegistry.registeredAnalyses()) {
            assertTrue("duplicate index " + entry.analysisIndex(),
                    indexes.add(Integer.valueOf(entry.analysisIndex())));
        }
        assertEquals(12, indexes.size());
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
