package flash.pipeline.bin;

import flash.pipeline.analyses.DrawAndSaveROIsAnalysis;
import flash.pipeline.analyses.DeconvolutionAnalysis;
import flash.pipeline.analyses.IntensityAnalysisV2;
import flash.pipeline.analyses.LineDistanceAnalysis;
import flash.pipeline.analyses.MasterAggregationAnalysis;
import flash.pipeline.analyses.SpatialAnalysis;
import flash.pipeline.analyses.SplitAndMergeImageChannelsAnalysis;
import flash.pipeline.analyses.StatisticalAnalysis;
import flash.pipeline.analyses.ThreeDObjectAnalysis;
import flash.pipeline.decontamination.SpectralDecontaminationAnalysis;
import flash.pipeline.export.ExcelSummaryExportAnalysis;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ConsumerAnalysesAgainstJsonProjectTest {
    private static final String FIXTURE = "channel-config/fixtures/3ch_classical_committed.json";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void threeDObjectAnalysisReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new ThreeDObjectAnalysis(), ReadMode.PARTIAL);
    }

    @Test
    public void spatialAnalysisReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new SpatialAnalysis(), ReadMode.PARTIAL);
    }

    @Test
    public void intensityAnalysisV2ReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new IntensityAnalysisV2(), ReadMode.PARTIAL);
    }

    @Test
    public void splitMergeReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new SplitAndMergeImageChannelsAnalysis(), ReadMode.PARTIAL);
    }

    @Test
    public void drawAndSaveROIsReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new DrawAndSaveROIsAnalysis(), ReadMode.PARTIAL);
    }

    @Test
    public void deconvolutionReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new DeconvolutionAnalysis(), ReadMode.COMPLETE);
    }

    @Test
    public void lineDistanceReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new LineDistanceAnalysis(), ReadMode.PARTIAL);
    }

    @Test
    public void aggregationReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new MasterAggregationAnalysis(), ReadMode.PARTIAL);
    }

    @Test
    public void statisticsReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new StatisticalAnalysis(), ReadMode.PARTIAL);
    }

    @Test
    public void excelReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new ExcelSummaryExportAnalysis(), ReadMode.PARTIAL);
    }

    @Test
    public void spectralDecontaminationReadsJsonOnlyProject() throws Exception {
        assertJsonOnlyReadProducesValidConfig(new SpectralDecontaminationAnalysis(), ReadMode.COMPLETE);
    }

    private void assertJsonOnlyReadProducesValidConfig(Object consumer, ReadMode mode) throws Exception {
        assertNotNull(consumer);
        File projectRoot = jsonOnlyProject();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        assertFalse(layout.channelDataWriteFile().exists());

        ChannelConfig channelConfig = ChannelConfigIO.read(layout.configurationWriteDir());
        BinConfig expected = ChannelConfigIO.toBinConfig(channelConfig);
        BinConfig actual = mode == ReadMode.COMPLETE
                ? BinConfigIO.readFromDirectory(projectRoot.getAbsolutePath())
                : BinConfigIO.readPartialFromDirectory(projectRoot.getAbsolutePath());

        assertValidMatchingConfig(expected, actual);
    }

    private File jsonOnlyProject() throws Exception {
        File projectRoot = temp.newFolder();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        File settingsDir = layout.configurationWriteDir();
        assertTrue(settingsDir.mkdirs());

        InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE);
        assertNotNull("Missing fixture " + FIXTURE, in);
        try {
            Files.copy(in, new File(settingsDir, ChannelConfigIO.FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            in.close();
        }
        return projectRoot;
    }

    private static void assertValidMatchingConfig(BinConfig expected, BinConfig actual) throws IOException {
        assertNotNull(actual);
        assertEquals(3, actual.numChannels());
        assertEquals(expected.channelNames, actual.channelNames);
        assertEquals(expected.channelColors, actual.channelColors);
        assertEquals(expected.channelThresholds, actual.channelThresholds);
        assertEquals(expected.channelSizes, actual.channelSizes);
        assertEquals(expected.channelMinMax, actual.channelMinMax);
        assertEquals(expected.channelIntensityThresholds, actual.channelIntensityThresholds);
        assertEquals(expected.segmentationMethods, actual.segmentationMethods);
        assertEquals(expected.channelFilterPresets, actual.channelFilterPresets);
        assertEquals(expected.zSliceMode, actual.zSliceMode);
        assertEquals(expected.zSliceConfigPresent, actual.zSliceConfigPresent);
        assertEquals(expected.clickConfigPresent, actual.clickConfigPresent);
        assertEquals(expected.zSliceSelections.keySet(), actual.zSliceSelections.keySet());
    }

    private enum ReadMode {
        PARTIAL,
        COMPLETE
    }
}
