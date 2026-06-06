package flash.pipeline.analyses;

import flash.pipeline.report.QualityReport;
import flash.pipeline.representative.RepresentativeStatistic;
import flash.pipeline.runrecord.LoadedRunParameters;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RepresentativeFigureAnalysisTest {

    @Test
    public void skeletonDeclaresHeadedInteractiveAnalysisWithoutBinRequirements() {
        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();

        assertTrue(analysis.requiresHeadedMode());
        assertFalse(analysis.benefitsFromRois());
        assertTrue(analysis.requiredBinFields().isEmpty());
        assertNotNull(analysis.configForTests());
    }

    @Test
    public void commonSettersAndStubExecuteAreHeadlessSafe() {
        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();

        analysis.setHeadless(true);
        analysis.setVerboseLogging(true);
        analysis.setSkipExisting(true);
        analysis.setParallelThreads(0);
        analysis.setImageCache(null);
        analysis.setLoaderThreads(0);
        analysis.setLoaderPercent(150);
        analysis.setUseTifCache(true);
        analysis.setQualityReport(new QualityReport());
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(null);
        analysis.execute("C:/tmp/flash");

        assertEquals(RepresentativeStatistic.NONE, analysis.configForTests().statistic);
        assertTrue(analysis.configForTests().statTable.isEmpty());
    }

    @Test
    public void loadedParameterStubReturnsResultAndReportsUnknownKeys() {
        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("future_key", "value");

        LoadedRunParameters.Result result = analysis.applyLoadedParameters(parameters);

        assertTrue(result.getAppliedKeys().isEmpty());
        assertTrue(result.getIgnoredKeys().contains("future_key"));
        LoadedRunParameters.Result empty = analysis.applyLoadedParameters(Collections.<String, Object>emptyMap());
        assertFalse(empty.hasIgnoredKeys());
    }
}
