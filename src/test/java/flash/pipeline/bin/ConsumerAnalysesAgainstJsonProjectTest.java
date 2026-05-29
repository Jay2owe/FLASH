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
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
        assertAnalysisLoaderProducesValidConfig(new ThreeDObjectAnalysis(), "loadBinConfig");
    }

    @Test
    public void spatialAnalysisReadsJsonOnlyProject() throws Exception {
        assertNoBinConfigReadPath(SpatialAnalysis.class);
    }

    @Test
    public void intensityAnalysisV2ReadsJsonOnlyProject() throws Exception {
        assertAnalysisLoaderProducesValidConfig(new IntensityAnalysisV2(), "loadBinConfig");
    }

    @Test
    public void splitMergeReadsJsonOnlyProject() throws Exception {
        assertAnalysisLoaderProducesValidConfig(new SplitAndMergeImageChannelsAnalysis(), "loadBinConfig");
    }

    @Test
    public void drawAndSaveROIsReadsJsonOnlyProject() throws Exception {
        assertAnalysisLoaderProducesValidConfig(new DrawAndSaveROIsAnalysis(), "loadBinConfig");
    }

    @Test
    public void deconvolutionReadsJsonOnlyProject() throws Exception {
        File projectRoot = jsonOnlyProject();
        String[] names = invokeDeconvolutionChannelResolver(projectRoot);
        assertEquals(Arrays.asList("DAPI", "IBA1", "GFAP"), Arrays.asList(names));
    }

    @Test
    public void lineDistanceReadsJsonOnlyProject() throws Exception {
        File projectRoot = jsonOnlyProject();
        BinConfig actual = invokeStaticBinConfigLoader(LineDistanceAnalysis.class,
                "readBinConfigForLineDrawing", projectRoot);
        assertValidMatchingConfig(expectedConfig(projectRoot), actual);
    }

    @Test
    public void aggregationReadsJsonOnlyProject() throws Exception {
        File projectRoot = jsonOnlyProject();
        Set<String> channels = invokeKnownIntensityChannels(projectRoot);
        assertEquals(new HashSet<String>(Arrays.asList("DAPI", "IBA1", "GFAP")), channels);
    }

    @Test
    public void statisticsReadsJsonOnlyProject() throws Exception {
        assertNoBinConfigReadPath(StatisticalAnalysis.class);
    }

    @Test
    public void excelReadsJsonOnlyProject() throws Exception {
        assertNoBinConfigReadPath(ExcelSummaryExportAnalysis.class);
    }

    @Test
    public void spectralDecontaminationReadsJsonOnlyProject() throws Exception {
        assertAnalysisLoaderProducesValidConfig(new SpectralDecontaminationAnalysis(), "loadBinConfig");
    }

    private void assertAnalysisLoaderProducesValidConfig(Object consumer, String methodName) throws Exception {
        File projectRoot = jsonOnlyProject();
        BinConfig actual = invokeBinConfigLoader(consumer, methodName, projectRoot);

        assertValidMatchingConfig(expectedConfig(projectRoot), actual);
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

    private static BinConfig expectedConfig(File projectRoot) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        ChannelConfig channelConfig = ChannelConfigIO.read(layout.configurationWriteDir());
        return ChannelConfigIO.toBinConfig(channelConfig, layout.configurationWriteDir());
    }

    private static BinConfig invokeBinConfigLoader(Object consumer, String methodName,
                                                   File projectRoot) throws Exception {
        assertNotNull(consumer);
        Method method = findMethod(consumer.getClass(), methodName, String.class);
        method.setAccessible(true);
        return (BinConfig) method.invoke(consumer, projectRoot.getAbsolutePath());
    }

    private static BinConfig invokeStaticBinConfigLoader(Class<?> consumerClass, String methodName,
                                                         File projectRoot) throws Exception {
        Method method = findMethod(consumerClass, methodName, String.class);
        method.setAccessible(true);
        return (BinConfig) method.invoke(null, projectRoot.getAbsolutePath());
    }

    private static String[] invokeDeconvolutionChannelResolver(File projectRoot) throws Exception {
        DeconvolutionAnalysis analysis = new DeconvolutionAnalysis();
        Method method = DeconvolutionAnalysis.class.getDeclaredMethod(
                "resolveChannelNames", String.class, MetadataDiagnostics.SeriesInfo.class);
        method.setAccessible(true);
        return (String[]) method.invoke(analysis, projectRoot.getAbsolutePath(), null);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> invokeKnownIntensityChannels(File projectRoot) throws Exception {
        Method method = MasterAggregationAnalysis.class.getDeclaredMethod(
                "knownIntensityChannels", String.class);
        method.setAccessible(true);
        return (Set<String>) method.invoke(null, projectRoot.getAbsolutePath());
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static void assertNoBinConfigReadPath(Class<?> consumerClass) throws Exception {
        String resource = "/" + consumerClass.getName().replace('.', '/') + ".class";
        InputStream in = consumerClass.getResourceAsStream(resource);
        assertNotNull("Missing bytecode resource for " + consumerClass.getName(), in);
        byte[] bytes;
        try {
            bytes = readAllBytes(in);
        } finally {
            in.close();
        }
        String bytecode = new String(bytes, "ISO-8859-1");
        assertFalse(consumerClass.getName() + " unexpectedly references BinConfigIO",
                bytecode.contains("flash/pipeline/bin/BinConfigIO"));
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
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

}
