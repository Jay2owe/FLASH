package flash.pipeline.cli;

import flash.pipeline.bin.BinField;
import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CLIArgumentParserTest {

    // ── hasCliOptions ──

    @Test
    public void hasCliOptions_withDir() {
        assertTrue(CLIArgumentParser.hasCliOptions("dir=[/path]"));
    }

    @Test
    public void hasCliOptions_withConfigDir() {
        assertTrue(CLIArgumentParser.hasCliOptions("config_dir=[/path]"));
    }

    @Test
    public void hasCliOptions_emptyString() {
        assertFalse(CLIArgumentParser.hasCliOptions(""));
    }

    @Test
    public void hasCliOptions_null() {
        assertFalse(CLIArgumentParser.hasCliOptions(null));
    }

    @Test
    public void usageNamesRunBinAsConfigurationSetup() {
        String usage = CLIArgumentParser.usage();

        assertTrue(usage.contains("run_bin               Set Up Configuration (0)"));
        assertFalse(usage.contains("Create Bin File"));
    }

    @Test
    public void hasCliOptions_noDir() {
        assertFalse(CLIArgumentParser.hasCliOptions("run_3d run_intensity"));
    }

    @Test
    public void hasCliOptions_doesNotMatchDirInsideLongerKey() {
        assertFalse(CLIArgumentParser.hasCliOptions("excel.metric_dir=[/tmp/results]"));
    }

    // ── parse: directory ──

    @Test
    public void parse_extractsDirectory() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp/test]");
        assertNotNull(cfg);
        assertEquals("/tmp/test", cfg.getDirectory());
    }

    @Test
    public void parse_bracketDelimitedPathWithSpaces() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[C:/path with spaces/data]");
        assertNotNull(cfg);
        assertEquals("C:/path with spaces/data", cfg.getDirectory());
    }

    @Test
    public void parse_quotedPathWithSpaces() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=\"C:/path with spaces/data\" run_3d");
        assertNotNull(cfg);
        assertEquals("C:/path with spaces/data", cfg.getDirectory());
        assertTrue(cfg.getSelectedAnalyses()[4]);
    }

    @Test
    public void parse_analysisFlagInsideBracketedPathIsNotSelected() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[C:/path with run_3d/data]");
        assertNotNull(cfg);
        assertFalse(cfg.getSelectedAnalyses()[4]);
    }

    @Test
    public void parse_analysisFlagInsideQuotedPathIsNotSelected() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=\"C:/path with run_3d/data\"");
        assertNotNull(cfg);
        assertFalse(cfg.getSelectedAnalyses()[4]);
    }

    @Test
    public void parse_malformedBracketedDirReturnsNullInsteadOfSwallowingFlags() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[C:/path with spaces run_3d");
        assertNull(cfg);
    }

    @Test
    public void parse_configDirFallback() {
        CLIConfig cfg = CLIArgumentParser.parse("config_dir=[/tmp/cfg]");
        assertNotNull(cfg);
        assertEquals("/tmp/cfg", cfg.getDirectory());
    }

    @Test
    public void parse_canonicalizesExistingDirectory() throws Exception {
        File dir = new File(".").getCanonicalFile();

        CLIConfig cfg = CLIArgumentParser.parse("dir=[" + dir.getPath() + "]");

        assertNotNull(cfg);
        assertEquals(dir.getCanonicalPath(), cfg.getDirectory());
    }

    @Test
    public void parse_missingDir_returnsNull() {
        CLIConfig cfg = CLIArgumentParser.parse("run_3d");
        assertNull(cfg);
    }

    @Test
    public void parse_null_returnsNull() {
        assertNull(CLIArgumentParser.parse(null));
    }

    @Test
    public void parse_emptyString_returnsNull() {
        assertNull(CLIArgumentParser.parse(""));
    }

    // ── parse: individual analysis flags ──

    @Test
    public void parse_individualFlags() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] run_3d run_intensity");
        assertNotNull(cfg);
        boolean[] sel = cfg.getSelectedAnalyses();
        assertTrue("run_3d -> index 4", sel[4]);
        assertTrue("run_intensity -> index 7", sel[7]);
        assertFalse("run_bin should be off", sel[0]);
    }

    @Test
    public void parse_runSpatialFlag() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] run_spatial");
        assertNotNull(cfg);
        boolean[] sel = cfg.getSelectedAnalyses();
        assertTrue("run_spatial -> index 5", sel[5]);
        assertFalse("run_distance should be off", sel[6]);
    }

    @Test
    public void parse_runSpectralDecontaminationFlag() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] run_spectral_decontamination");
        assertNotNull(cfg);
        boolean[] sel = cfg.getSelectedAnalyses();
        assertTrue("run_spectral_decontamination -> index 11", sel[11]);
        assertFalse("run_excel should be off", sel[10]);
    }

    // ── parse: analyses list ──

    @Test
    public void parse_analysesList() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] analyses=0,2,6,11");
        assertNotNull(cfg);
        boolean[] sel = cfg.getSelectedAnalyses();
        assertTrue(sel[0]);
        assertTrue(sel[2]);
        assertTrue(sel[6]);
        assertTrue("index 11 -> spectral decontamination", sel[11]);
        assertFalse(sel[1]);
    }

    @Test
    public void parse_flagsAndAnalysesList_union() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] run_spatial analyses=0,6");
        assertNotNull(cfg);
        boolean[] sel = cfg.getSelectedAnalyses();
        assertTrue("from analyses list", sel[0]);
        assertTrue("from run_spatial flag", sel[5]);
        assertTrue("from analyses list", sel[6]);
    }

    // ── parse: boolean defaults ──

    @Test
    public void parse_defaultHeadless() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp]");
        assertNotNull(cfg);
        assertTrue(cfg.isHeadless());
    }

    @Test
    public void parse_defaultParallel() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp]");
        assertNotNull(cfg);
        assertTrue(cfg.isParallel());
    }

    @Test
    public void parse_defaultAutoAggregate() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp]");
        assertNotNull(cfg);
        assertTrue(cfg.isAutoAggregate());
    }

    @Test
    public void parse_defaultQcReport() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp]");
        assertNotNull(cfg);
        assertTrue(cfg.isQcReport());
    }

    // ── parse: threads ──

    @Test
    public void parse_threads() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] threads=4");
        assertNotNull(cfg);
        assertEquals(4, cfg.getThreads());
    }

    @Test
    public void parse_threadsClamped() {
        int maxCores = Runtime.getRuntime().availableProcessors();
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] threads=99999");
        assertNotNull(cfg);
        assertEquals(maxCores, cfg.getThreads());
    }

    @Test
    public void parse_threadsMinimum() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] threads=0");
        assertNotNull(cfg);
        assertEquals(1, cfg.getThreads());
    }

    // ── parse: loader_percent ──

    @Test
    public void parse_loaderPercent() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] loader_percent=80");
        assertNotNull(cfg);
        assertEquals(80, cfg.getLoaderPercent());
    }

    @Test
    public void parse_loaderPercentClampedLow() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] loader_percent=0");
        assertNotNull(cfg);
        assertEquals(1, cfg.getLoaderPercent());
    }

    @Test
    public void parse_loaderPercentClampedHigh() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] loader_percent=200");
        assertNotNull(cfg);
        assertEquals(100, cfg.getLoaderPercent());
    }

    // ── parse: overwrite ──

    @Test
    public void parse_overwriteSkip() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] overwrite=skip");
        assertNotNull(cfg);
        assertEquals("Skip Existing", cfg.getOverwriteBehavior());
    }

    @Test
    public void parse_overwriteAuto() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] overwrite=auto");
        assertNotNull(cfg);
        assertEquals("Auto-Overwrite", cfg.getOverwriteBehavior());
    }

    @Test
    public void parse_overwriteDefault() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp]");
        assertNotNull(cfg);
        assertEquals("Auto-Overwrite", cfg.getOverwriteBehavior());
    }

    // ── parse: boolean flags ──

    @Test
    public void parse_noAggregateFlag() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] no_aggregate");
        assertNotNull(cfg);
        assertFalse(cfg.isAutoAggregate());
    }

    @Test
    public void parse_noQcFlag() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] no_qc");
        assertNotNull(cfg);
        assertFalse(cfg.isQcReport());
    }

    @Test
    public void parse_aggressiveMemoryFlag() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] aggressive_memory");
        assertNotNull(cfg);
        assertTrue(cfg.isAggressiveMemory());
    }

    @Test
    public void parse_verboseFlag() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] verbose");
        assertNotNull(cfg);
        assertTrue(cfg.isVerbose());
    }

    @Test
    public void parse_tifCacheFlag() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] tif_cache");
        assertNotNull(cfg);
        assertTrue(cfg.isTifCache());
    }

    @Test
    public void parse_headlessBinFieldsWithoutSelectingCreateBin() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] run_intensity "
                + "channel_names=DAPI,GFP "
                + "channel_colors=Cyan,Green "
                + "object_thresholds=default,1500 "
                + "particle_sizes=100-Infinity,50-Infinity "
                + "display_min_max=None,0-4095 "
                + "intensity_thresholds=default,500 "
                + "segmentation_methods=cellpose:30:nuclei,classical "
                + "filter_presets=Default,ramified_cells_microglia_astrocytes "
                + "z_slice_mode=full");

        assertNotNull(cfg);
        assertEquals("DAPI,GFP", cfg.getBinFieldValue(BinField.CHANNEL_NAMES));
        assertEquals("Cyan,Green", cfg.getBinFieldValue(BinField.CHANNEL_COLORS));
        assertEquals("default,1500", cfg.getBinFieldValue(BinField.OBJECT_THRESHOLDS));
        assertEquals("100-Infinity,50-Infinity", cfg.getBinFieldValue(BinField.PARTICLE_SIZES));
        assertEquals("None,0-4095", cfg.getBinFieldValue(BinField.DISPLAY_MIN_MAX));
        assertEquals("default,500", cfg.getBinFieldValue(BinField.INTENSITY_THRESHOLDS));
        assertEquals("cellpose:30:nuclei,classical", cfg.getBinFieldValue(BinField.SEGMENTATION_METHODS));
        assertEquals("Default,ramified_cells_microglia_astrocytes",
                cfg.getBinFieldValue(BinField.FILTER_PRESETS));
        assertEquals("full", cfg.getBinFieldValue(BinField.Z_SLICE));
        assertFalse("stage-09 bin fields are consumed by the dispatcher, not Set Up Configuration",
                cfg.getSelectedAnalyses()[0]);
        assertTrue(cfg.getSelectedAnalyses()[7]);
    }

    // ── getValue: token boundary + bracket nesting (Step 09) ──

    @Test
    public void getValue_skipsKeyEmbeddedInLongerKey() {
        // 'dir' must NOT match the 'dir' inside 'metric_dir='.
        assertEquals("/data", CLIArgumentParser.getValue(
                "excel.metric_dir=raw_values dir=[/data]", "dir"));
    }

    @Test
    public void getValue_handlesBracketsInsideBracketedPath() {
        // Inner ']' is not followed by whitespace, so the outer pair wins.
        assertEquals("/Users/foo [bar]/data", CLIArgumentParser.getValue(
                "dir=[/Users/foo [bar]/data] verbose=true", "dir"));
    }

    @Test
    public void getValue_handlesEscapedClosingBracketInBracketedPath() {
        assertEquals("/Users/foo ] bar/data", CLIArgumentParser.getValue(
                "dir=[/Users/foo \\] bar/data] verbose=true", "dir"));
    }

    @Test
    public void getValue_dottedKeyAtStart() {
        assertEquals("raw_values", CLIArgumentParser.getValue(
                "excel.metric_dir=raw_values verbose=true", "excel.metric_dir"));
    }

    @Test
    public void serialize_roundTripsHeadlessBinFields() {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] run_intensity "
                + "channel_names=DAPI,GFP intensity_thresholds=default,default z_slice_mode=full");

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));

        assertNotNull(reparsed);
        assertEquals("DAPI,GFP", reparsed.getBinFieldValue(BinField.CHANNEL_NAMES));
        assertEquals("default,default", reparsed.getBinFieldValue(BinField.INTENSITY_THRESHOLDS));
        assertEquals("full", reparsed.getBinFieldValue(BinField.Z_SLICE));
        assertFalse(reparsed.getSelectedAnalyses()[0]);
        assertTrue(reparsed.getSelectedAnalyses()[7]);
    }

    @Test
    public void parse_intensitySpatialOptionsRoundTrip() {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "intensity.spatial=true "
                + "intensity.spatial.analyses=patchiness,hotspots,depth,anisotropy,crossmark,mi,distance_shell "
                + "intensity.spatial.mip=true "
                + "intensity.spatial.native3d=true "
                + "intensity.spatial.overlays=true "
                + "intensity.spatial.shell_width_um=10 "
                + "intensity.spatial.shell_count=5 "
                + "intensity.spatial.tile_um=50,100,250 "
                + "intensity.spatial.granularity_um=2,4,8,16,32,64 "
                + "intensity.spatial.texture_k=4 "
                + "intensity.spatial.permutations=199 "
                + "intensity.spatial.seed=1");

        assertNotNull(parsed);
        assertTrue(parsed.getSelectedAnalyses()[7]);
        assertSpatialCliFields(parsed);

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));

        assertNotNull(reparsed);
        assertTrue(reparsed.getSelectedAnalyses()[7]);
        assertSpatialCliFields(reparsed);
    }

    @Test
    public void parse_intensitySpatialSourceModeOverridesLegacyMipFlag() {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "intensity.spatial=true "
                + "intensity.spatial.analyses=patchiness "
                + "intensity.spatial.mip=true "
                + "intensity.spatial.source=full_stack");

        assertEquals(IntensitySpatialConfig.SpatialSourceMode.FULL_STACK,
                parsed.getIntensity().getSpatialSourceMode());

        IntensitySpatialConfig merged = parsed.getIntensity().mergeSpatialConfig(
                IntensitySpatialConfig.disabled(),
                1,
                new boolean[]{false},
                null);

        assertEquals(IntensitySpatialConfig.SpatialSourceMode.FULL_STACK,
                merged.getSpatialSourceMode());
        assertFalse(merged.isMipEnabled());
    }

    @Test
    public void intensitySpatialCliMergeEnforcesChannelAndBinarizationLocks() {
        CLIConfig crossChannelParsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "intensity.spatial=true "
                + "intensity.spatial.analyses=crossmark,patchiness");
        final List<String> crossChannelLogs = new ArrayList<String>();

        IntensitySpatialConfig crossChannelMerged = crossChannelParsed.getIntensity().mergeSpatialConfig(
                IntensitySpatialConfig.disabled(),
                1,
                new boolean[]{false},
                new IntensitySpatialConfig.LockLogger() {
                    @Override
                    public void log(String message) {
                        crossChannelLogs.add(message);
                    }
                });

        assertTrue(crossChannelMerged.isEnabled());
        assertTrue(crossChannelMerged.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertFalse(crossChannelMerged.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
        assertTrue(join(crossChannelLogs).contains("at least two channels"));

        CLIConfig distanceShellParsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "intensity.spatial=true "
                + "intensity.spatial.analyses=distance_shell,patchiness");
        final List<String> distanceShellLogs = new ArrayList<String>();

        IntensitySpatialConfig distanceShellMerged = distanceShellParsed.getIntensity().mergeSpatialConfig(
                IntensitySpatialConfig.disabled(),
                2,
                new boolean[]{false, false},
                new IntensitySpatialConfig.LockLogger() {
                    @Override
                    public void log(String message) {
                        distanceShellLogs.add(message);
                    }
                });

        assertTrue(distanceShellMerged.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertFalse(distanceShellMerged.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
        assertTrue(join(distanceShellLogs).contains("binarized partner channel"));
    }

    @Test
    public void intensitySpatialDerivedConfigEnforcesNative3dSliceLock() {
        IntensitySpatialConfig requested = IntensitySpatialConfig.builder()
                .enabled(true)
                .native3dEnabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D)
                .build();
        final List<String> logs = new ArrayList<String>();

        IntensitySpatialConfig validated = requested.validateForChannelSetup(
                2,
                new boolean[]{true, false},
                Integer.valueOf(3),
                new IntensitySpatialConfig.LockLogger() {
                    @Override
                    public void log(String message) {
                        logs.add(message);
                    }
                });

        assertFalse(validated.isNative3dEnabled());
        assertFalse(validated.getEnabledAnalyses().contains(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D));
        assertTrue(join(logs).contains("at least 5 slices"));
    }

    private static void assertSpatialCliFields(CLIConfig cfg) {
        CLIConfig.IntensityConfig intensity = cfg.getIntensity();
        assertEquals(Boolean.TRUE, intensity.getSpatialEnabled());
        assertTrue(intensity.getSpatialAnalyses().contains(IntensitySpatialConfig.AnalysisKey.PATCHINESS));
        assertTrue(intensity.getSpatialAnalyses().contains(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN));
        assertTrue(intensity.getSpatialAnalyses().contains(IntensitySpatialConfig.AnalysisKey.DEPTH_PROFILE));
        assertTrue(intensity.getSpatialAnalyses().contains(IntensitySpatialConfig.AnalysisKey.ANISOTROPY));
        assertTrue(intensity.getSpatialAnalyses().contains(IntensitySpatialConfig.AnalysisKey.CROSSMARK));
        assertTrue(intensity.getSpatialAnalyses().contains(IntensitySpatialConfig.AnalysisKey.ENTROPY_MI));
        assertTrue(intensity.getSpatialAnalyses().contains(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL));
        assertEquals(Boolean.TRUE, intensity.getSpatialMipEnabled());
        assertEquals(Boolean.TRUE, intensity.getSpatialNative3dEnabled());
        assertEquals(Boolean.TRUE, intensity.getSpatialOverlaysEnabled());
        assertEquals(10.0, intensity.getSpatialShellWidthUm().doubleValue(), 0.0001);
        assertEquals(Integer.valueOf(5), intensity.getSpatialShellCount());
        assertArrayEquals(new double[]{50.0, 100.0, 250.0},
                intensity.getSpatialTileScalesUm(), 0.0001);
        assertArrayEquals(new double[]{2.0, 4.0, 8.0, 16.0, 32.0, 64.0},
                intensity.getSpatialGranularityScalesUm(), 0.0001);
        assertEquals(Integer.valueOf(4), intensity.getSpatialTextureClassCount());
        assertEquals(Integer.valueOf(199), intensity.getSpatialPermutations());
        assertEquals(Long.valueOf(1L), intensity.getSpatialSeed());
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(value);
        }
        return sb.toString();
    }
}
