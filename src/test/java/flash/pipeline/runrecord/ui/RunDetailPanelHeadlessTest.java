package flash.pipeline.runrecord.ui;

import flash.pipeline.runrecord.RunRecord;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class RunDetailPanelHeadlessTest {

    @Test
    public void parameterTreeContainsNestedMapAndListNodes() {
        RunRecord record = new RunRecord();
        record.runId = "RUN123456";
        record.analysis = "SpatialAnalysis";
        record.status = "ok";

        Map<String, Object> segmentation = new LinkedHashMap<String, Object>();
        segmentation.put("method", "stardist");
        segmentation.put("threshold", Double.valueOf(0.42));
        Map<String, Object> advanced = new LinkedHashMap<String, Object>();
        advanced.put("normalize", Boolean.TRUE);
        segmentation.put("advanced", advanced);

        record.parameters.put("segmentation", segmentation);
        record.parameters.put("channels", Arrays.<Object>asList("DAPI", "Iba1"));

        RunDetailPanel panel = new RunDetailPanel();
        panel.setRecord(record);

        assertTrue(panel.parameterTreeContainsForTests("segmentation"));
        assertTrue(panel.parameterTreeContainsForTests("method: stardist"));
        assertTrue(panel.parameterTreeContainsForTests("threshold: 0.42"));
        assertTrue(panel.parameterTreeContainsForTests("normalize: true"));
        assertTrue(panel.parameterTreeContainsForTests("[1]: Iba1"));
    }
}
