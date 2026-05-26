package flash.pipeline.bin;

import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.zslice.ZSliceMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class BinConfigIOTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File writeBinFile(String... lines) throws IOException {
        File dir = tempFolder.newFolder("data");
        File bin = new File(dir, ".bin");
        bin.mkdirs();
        File channelData = new File(bin, "Channel_Data.txt");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        Files.write(channelData.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private void writeFilterMacro(File dir, int channelIndex1Based, String content) throws IOException {
        File macro = new File(new File(dir, ".bin"), "C" + channelIndex1Based + "_Filters.ijm");
        Files.write(macro.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeSavedCustomFilterPreset(File dir, String presetName, String content) throws IOException {
        File presetDir = new File(new File(dir, ".bin"), "Custom Filter Presets");
        presetDir.mkdirs();
        Files.write(new File(presetDir, presetName + ".ijm").toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeNewSavedCustomFilterPreset(File dir, String presetName, String content) throws IOException {
        File presetDir = new File(dir, "FLASH/.settings/Presets/Custom Filter Presets");
        presetDir.mkdirs();
        Files.write(new File(presetDir, presetName + ".ijm").toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeZSliceSelections(File dir, String... rows) throws IOException {
        File csv = new File(new File(dir, ".bin"), "ZSlice_Selections.csv");
        StringBuilder sb = new StringBuilder();
        sb.append("SeriesIndex,SeriesName,TotalSlices,StartSlice,EndSlice,SliceCount,SelectionMode\n");
        for (String row : rows) {
            sb.append(row).append("\n");
        }
        Files.write(csv.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static File configurationDir(File dir) {
        return new File(dir, "FLASH/Set Up Configuration/.settings");
    }

    private static File configurationFile(File dir, String name) {
        return new File(configurationDir(dir), name);
    }

    private void assertSameBinConfigFields(BinConfig expected, BinConfig actual) {
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
        assertEquals(expected.zSliceSelections.keySet(), actual.zSliceSelections.keySet());
        for (Integer key : expected.zSliceSelections.keySet()) {
            assertEquals(expected.zSliceSelections.get(key).seriesIndex, actual.zSliceSelections.get(key).seriesIndex);
            assertEquals(expected.zSliceSelections.get(key).seriesName, actual.zSliceSelections.get(key).seriesName);
            assertEquals(expected.zSliceSelections.get(key).totalSlices, actual.zSliceSelections.get(key).totalSlices);
            assertEquals(expected.zSliceSelections.get(key).range, actual.zSliceSelections.get(key).range);
        }
    }

    private static String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    @Test
    public void readPartialMissingFolder_returnsEmptyConfig() {
        File dir = new File(tempFolder.getRoot(), "does-not-exist");

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(0, cfg.numChannels());
        assertFalse(cfg.hasChannelNames());
        assertFalse(cfg.hasChannelColors());
        assertFalse(cfg.hasChannelThresholds());
        assertFalse(cfg.hasChannelSizes());
        assertFalse(cfg.hasChannelMinMax());
        assertFalse(cfg.hasChannelIntensityThresholds());
        assertFalse(cfg.hasSegmentationMethods());
        assertFalse(cfg.hasChannelFilterPresets());
        assertFalse(cfg.hasZSliceConfig());
    }

    @Test
    public void readPartialMissingFile_returnsEmptyConfig() throws IOException {
        File dir = tempFolder.newFolder("empty");

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(0, cfg.numChannels());
        assertTrue(cfg.channelNames.isEmpty());
        assertFalse(cfg.hasChannelNames());
    }

    @Test
    public void readPartialLine1Only_populatesOnlyChannelNames() throws IOException {
        File dir = writeBinFile("DAPI GFP RFP");

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(3, cfg.numChannels());
        assertEquals("DAPI", cfg.channelNames.get(0));
        assertEquals("GFP", cfg.channelNames.get(1));
        assertEquals("RFP", cfg.channelNames.get(2));
        assertTrue(cfg.hasChannelNames());
        assertTrue(cfg.channelColors.isEmpty());
        assertFalse(cfg.hasChannelColors());
        assertFalse(cfg.hasChannelThresholds());
        assertFalse(cfg.hasChannelSizes());
    }

    @Test
    public void readPartialLines1To3_populatesOnlyPresentParameters() throws IOException {
        File dir = writeBinFile(
                "DAPI GFP",
                "Blue Green",
                "default 500"
        );

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(java.util.Arrays.asList("DAPI", "GFP"), cfg.channelNames);
        assertEquals(java.util.Arrays.asList("Blue", "Green"), cfg.channelColors);
        assertEquals(java.util.Arrays.asList("default", "500"), cfg.channelThresholds);
        assertTrue(cfg.hasChannelNames());
        assertTrue(cfg.hasChannelColors());
        assertTrue(cfg.hasChannelThresholds());
        assertTrue(cfg.channelSizes.isEmpty());
        assertFalse(cfg.hasChannelSizes());
    }

    @Test
    public void readPartialFull9LineFile_matchesStrictReaderFieldByField() throws IOException {
        File dir = writeBinFile(
                "DAPI GFP",
                "Blue Green",
                "default 500",
                "100-Infinity 50-5000",
                "None 0-4095",
                "default 750",
                "classical stardist:0.5:0.4",
                "default clustered_large",
                "zslice:same_count"
        );
        writeZSliceSelections(dir,
                "0,Series A,40,11,30,20,SAME_COUNT",
                "1,Series B,36,9,28,20,SAME_COUNT"
        );

        BinConfig strict = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        BinConfig partial = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertSameBinConfigFields(strict, partial);
        assertTrue(partial.hasChannelNames());
        assertTrue(partial.hasChannelColors());
        assertTrue(partial.hasChannelThresholds());
        assertTrue(partial.hasChannelSizes());
        assertTrue(partial.hasChannelMinMax());
        assertTrue(partial.hasChannelIntensityThresholds());
        assertTrue(partial.hasSegmentationMethods());
        assertTrue(partial.hasChannelFilterPresets());
        assertTrue(partial.hasZSliceConfig());
    }

    @Test
    public void readPartialLegacyFileWithoutFilterPresetLineInfersSavedFilterMacros() throws IOException {
        File dir = writeBinFile(
                "DAPI GFP",
                "Blue Green",
                "default 500",
                "100-Infinity 50-5000",
                "None 0-4095",
                "default 750",
                "classical classical"
        );
        writeFilterMacro(dir, 1, NamedFilterLoader.loadFilterContent("Default"));
        writeFilterMacro(dir, 2, NamedFilterLoader.loadFilterContent("Ramified Cells (Microglia/Astrocytes)"));

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(java.util.Arrays.asList("Default", "Ramified Cells (Microglia/Astrocytes)"),
                cfg.channelFilterPresets);
        assertTrue(cfg.hasChannelFilterPresets());
    }

    @Test
    public void readPartialLegacyFileWithoutSegmentationLineDefaultsClassical() throws IOException {
        File dir = writeBinFile(
                "DAPI GFP",
                "Blue Green",
                "default 500",
                "100-Infinity 50-5000",
                "None 0-4095",
                "default 750"
        );

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(java.util.Arrays.asList("classical", "classical"),
                cfg.segmentationMethods);
        assertTrue(cfg.hasSegmentationMethods());
    }

    @Test
    public void readPartialBlankSegmentationLineRemainsMissing() throws IOException {
        File dir = writeBinFile(
                "DAPI GFP",
                "Blue Green",
                "default 500",
                "100-Infinity 50-5000",
                "None 0-4095",
                "default 750",
                ""
        );

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertTrue(cfg.segmentationMethods.isEmpty());
        assertFalse(cfg.hasSegmentationMethods());
    }

    @Test
    public void writeFromConfigWritesFilterPresetMacrosForDirectAndCliSetup() throws IOException {
        File dir = tempFolder.newFolder("writeFilterMacros");
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("Iba1");
        cfg.channelFilterPresets.add("default");
        cfg.channelFilterPresets.add("ramified_cells_microglia_astrocytes");

        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);

        File bin = configurationDir(dir);
        assertEquals(normalize(NamedFilterLoader.loadFilterContent("Default")),
                normalize(readFile(new File(bin, "C1_Filters.ijm"))));
        assertEquals(normalize(NamedFilterLoader.loadFilterContent("Ramified Cells (Microglia/Astrocytes)")),
                normalize(readFile(new File(bin, "C2_Filters.ijm"))));
        assertTrue(new File(bin, "defaultFilter.ijm").isFile());
    }

    @Test
    public void readPartialBlankLine5_leavesMinMaxUnpopulated() throws IOException {
        File dir = writeBinFile(
                "DAPI GFP",
                "Blue Green",
                "default 500",
                "100-Infinity 50-5000",
                "",
                "default 750",
                "classical classical",
                "default default",
                "zslice:full"
        );

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertTrue(cfg.channelMinMax.isEmpty());
        assertFalse(cfg.hasChannelMinMax());
        assertEquals(java.util.Arrays.asList("default", "750"), cfg.channelIntensityThresholds);
        assertTrue(cfg.hasChannelIntensityThresholds());
    }

    @Test
    public void readPartialExtraTrailingLines_ignoresExtras() throws IOException {
        File dir = writeBinFile(
                "DAPI",
                "Blue",
                "default",
                "100-Infinity",
                "None",
                "default",
                "classical",
                "default",
                "zslice:full",
                "unexpected",
                "also-unexpected"
        );

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(1, cfg.numChannels());
        assertEquals("DAPI", cfg.channelNames.get(0));
        assertEquals("Default", cfg.channelFilterPresets.get(0));
        assertEquals(ZSliceMode.FULL, cfg.zSliceMode);
        assertTrue(cfg.hasZSliceConfig());
    }

    @Test
    public void readPartialMalformedZSliceSelections_fallsBackToFullMode() throws IOException {
        File dir = writeBinFile(
                "DAPI",
                "Blue",
                "default",
                "100-Infinity",
                "None",
                "default",
                "classical",
                "default",
                "zslice:per_image"
        );
        writeZSliceSelections(dir, "not-a-number,Series A,25,5,15,11,PER_IMAGE");

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(ZSliceMode.FULL, cfg.zSliceMode);
        assertFalse(cfg.hasZSliceConfig());
        assertTrue(cfg.zSliceSelections.isEmpty());
    }

    @Test
    public void readValid7LineFile() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP RFP",
            "Blue Green Red",
            "default 500 1000",
            "100-Infinity 50-5000 200-10000",
            "None 0-4095 100-3000",
            "default 750 default",
            "classical stardist:0.5:0.4 classical"
        );
        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(3, cfg.numChannels());
        assertEquals("DAPI", cfg.channelNames.get(0));
        assertEquals("GFP", cfg.channelNames.get(1));
        assertEquals("RFP", cfg.channelNames.get(2));
        assertEquals("Blue", cfg.channelColors.get(0));
        assertEquals("500", cfg.channelThresholds.get(1));
        assertEquals("50-5000", cfg.channelSizes.get(1));
        assertEquals("0-4095", cfg.channelMinMax.get(1));
        assertEquals("750", cfg.channelIntensityThresholds.get(1));
        assertEquals("stardist:0.5:0.4", cfg.segmentationMethods.get(1));
        assertTrue(cfg.isStarDist(1));
        assertFalse(cfg.isStarDist(0));
        assertEquals("Default", cfg.channelFilterPresets.get(0));
    }

    @Test
    public void readCellposeToken_preservesMethodAndParsesAsCellpose() throws IOException {
        File dir = writeBinFile(
                "DAPI IBA1",
                "Blue Cyan",
                "default default",
                "100-Infinity 100-Infinity",
                "None None",
                "default default",
                "classical cellpose:30.0:cyto3:0.4:0.0:gpu=true:chan2=0"
        );

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals("cellpose:30.0:cyto3:0.4:0.0:gpu=true:chan2=0", cfg.segmentationMethods.get(1));
        assertTrue(cfg.isCellpose(1));
        assertEquals(30.0, cfg.getCellposeDiameter(1), 0.001);
        assertEquals("cellpose_cyto3", cfg.getCellposeModel(1));
        assertTrue(cfg.getCellposeUseGpu(1));
        assertEquals(0, cfg.getCellposeSecondChannel(1));
    }

    @Test
    public void readValid8LineFile_loadsStoredFilterPresets() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP RFP",
            "Blue Green Red",
            "default 500 1000",
            "100-Infinity 50-5000 200-10000",
            "None 0-4095 100-3000",
            "default 750 default",
            "classical stardist:0.5:0.4 classical",
            "default clustered_large custom"
        );

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals("Default", cfg.channelFilterPresets.get(0));
        assertEquals("Clustered Large", cfg.channelFilterPresets.get(1));
        assertEquals("Custom", cfg.channelFilterPresets.get(2));
    }

    @Test
    public void readValid8LineFile_preservesUserNamedFilterPresetWithSpaces() throws IOException {
        String encoded = BinConfigIO.encodeFilterPresetToken("IBA1 cleanup filter");
        assertEquals("custom_filter:IBA1%20cleanup%20filter", encoded);
        File dir = writeBinFile(
                "IBA1",
                "Green",
                "default",
                "100-Infinity",
                "None",
                "default",
                "classical",
                encoded
        );

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals("IBA1 cleanup filter", cfg.channelFilterPresets.get(0));
    }

    @Test
    public void readValid8LineFile_preservesLegacyBase64UserFilterToken() throws IOException {
        File dir = writeBinFile(
                "IBA1",
                "Green",
                "default",
                "100-Infinity",
                "None",
                "default",
                "classical",
                "userfilter_SUJBMSBjbGVhbnVwIGZpbHRlcg"
        );

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals("IBA1 cleanup filter", cfg.channelFilterPresets.get(0));
    }

    @Test
    public void updateFilterPresets_writesReadableCustomFilterNameToLine8() throws IOException {
        File dir = writeBinFile(
                "IBA1 GFAP",
                "Green Magenta",
                "default default",
                "100-Infinity 100-Infinity",
                "None None",
                "default default",
                "classical classical",
                "default custom",
                "zslice:full"
        );

        BinConfigIO.updateFilterPresets(dir.getAbsolutePath(),
                java.util.Arrays.asList("IBA1 cleanup filter", "Default"));

        java.util.List<String> lines = Files.readAllLines(
                configurationFile(dir, "Channel_Data.txt").toPath(),
                StandardCharsets.UTF_8);
        assertEquals("custom_filter:IBA1%20cleanup%20filter\tdefault", lines.get(7));
        assertEquals("zslice:full", lines.get(8));
    }

    @Test
    public void readValid8LineFile_usesSavedCustomNameWhenStoredPresetIsStaleDefault() throws IOException {
        String customMacro = "run(\"Median...\", \"radius=9 stack\");\n";
        File dir = writeBinFile(
                "IBA1",
                "Green",
                "default",
                "100-Infinity",
                "None",
                "default",
                "classical",
                "default"
        );
        writeFilterMacro(dir, 1, customMacro);
        writeSavedCustomFilterPreset(dir, "IBA1 cleanup filter", customMacro);

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals("IBA1 cleanup filter", cfg.channelFilterPresets.get(0));
    }

    @Test
    public void readValid8LineFile_usesSavedCustomNameWhenStoredPresetIsGenericCustom() throws IOException {
        String customMacro = "run(\"Subtract Background...\", \"rolling=75 stack\");\n";
        File dir = writeBinFile(
                "GFAP",
                "Magenta",
                "default",
                "100-Infinity",
                "None",
                "default",
                "classical",
                "custom"
        );
        writeFilterMacro(dir, 1, customMacro);
        writeSavedCustomFilterPreset(dir, "GFAP cleanup filter", customMacro);

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals("GFAP cleanup filter", cfg.channelFilterPresets.get(0));
    }

    @Test
    public void readValid8LineFile_usesNewSavedCustomPresetFolder() throws IOException {
        String customMacro = "run(\"Median...\", \"radius=12 stack\");\n";
        File dir = tempFolder.newFolder("newCustomPresetRead");
        File configDir = configurationDir(dir);
        assertTrue(configDir.mkdirs());
        Files.write(new File(configDir, "Channel_Data.txt").toPath(),
                ("IBA1\n"
                        + "Green\n"
                        + "default\n"
                        + "100-Infinity\n"
                        + "None\n"
                        + "default\n"
                        + "classical\n"
                        + "custom\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(configDir, "C1_Filters.ijm").toPath(), customMacro.getBytes(StandardCharsets.UTF_8));
        writeNewSavedCustomFilterPreset(dir, "IBA1 cleanup filter", customMacro);

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals("IBA1 cleanup filter", cfg.channelFilterPresets.get(0));
    }

    @Test
    public void readValid8LineFile_usesGenericCustomWhenStoredPresetIsStaleDefaultAndNoNameMatches() throws IOException {
        File dir = writeBinFile(
                "IBA1",
                "Green",
                "default",
                "100-Infinity",
                "None",
                "default",
                "classical",
                "default"
        );
        writeFilterMacro(dir, 1, "run(\"Median...\", \"radius=11 stack\");\n");

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals("Custom", cfg.channelFilterPresets.get(0));
    }

    @Test
    public void readValid9LineFile_loadsZSliceModeAndSelections() throws IOException {
        File dir = writeBinFile(
                "DAPI GFP",
                "Blue Green",
                "default 500",
                "100-Infinity 50-5000",
                "None 0-4095",
                "default 750",
                "classical classical",
                "default custom",
                "zslice:same_count"
        );
        writeZSliceSelections(dir,
                "0,Series A,40,11,30,20,SAME_COUNT",
                "1,Series B,36,9,28,20,SAME_COUNT"
        );

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(ZSliceMode.SAME_COUNT, cfg.zSliceMode);
        assertTrue(cfg.usesZSliceSubset());
        assertEquals("11-30", cfg.getZSliceRange(0).toToken());
        assertEquals("9-28", cfg.getZSliceRange(1).toToken());
    }

    @Test
    public void readSelectionsWithoutModeLine_defaultsToPerImageWhenCsvExists() throws IOException {
        File dir = writeBinFile(
                "DAPI",
                "Blue",
                "default",
                "100-Infinity",
                "None",
                "default",
                "classical",
                "default"
        );
        writeZSliceSelections(dir,
                "0,Series A,25,5,15,11,PER_IMAGE"
        );

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(ZSliceMode.PER_IMAGE, cfg.zSliceMode);
        assertEquals("5-15", cfg.getZSliceRange(0).toToken());
    }

    @Test
    public void readLegacyFile_infersFilterPresetsFromFilterMacros() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP",
            "Blue Green",
            "default 500",
            "100-Infinity 50-5000",
            "None 0-4095",
            "default 750",
            "classical classical"
        );
        writeFilterMacro(dir, 1, NamedFilterLoader.loadFilterContent("Clustered Large"));
        writeFilterMacro(dir, 2, "run(\"Median...\", \"radius=9\");\n");

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals("Clustered Large", cfg.channelFilterPresets.get(0));
        assertEquals("Custom", cfg.channelFilterPresets.get(1));
    }

    @Test
    public void readValid4LineFile_defaultsForOptionalLines() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP",
            "Blue Green",
            "default 500",
            "100-Infinity 50-5000"
        );
        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(2, cfg.numChannels());
        assertEquals("None", cfg.channelMinMax.get(0));
        assertEquals("None", cfg.channelMinMax.get(1));
        assertEquals("default", cfg.channelIntensityThresholds.get(0));
        assertEquals("classical", cfg.segmentationMethods.get(0));
        assertEquals("classical", cfg.segmentationMethods.get(1));
    }

    @Test
    public void readValid5LineFile_defaultsForLines6And7() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP",
            "Blue Green",
            "default 500",
            "100-Infinity 50-5000",
            "None 0-4095"
        );
        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(2, cfg.numChannels());
        assertEquals("0-4095", cfg.channelMinMax.get(1));
        assertEquals("default", cfg.channelIntensityThresholds.get(0));
        assertEquals("classical", cfg.segmentationMethods.get(0));
    }

    @Test(expected = IOException.class)
    public void readMalformedFile_tooFewLines() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP",
            "Blue Green"
        );
        BinConfigIO.readFromDirectory(dir.getAbsolutePath());
    }

    @Test(expected = IOException.class)
    public void readMissingFile() throws IOException {
        File dir = tempFolder.newFolder("empty");
        BinConfigIO.readFromDirectory(dir.getAbsolutePath());
    }

    @Test
    public void splitTokens_handlesExtraWhitespaceInLegacyFile() throws IOException {
        File dir = writeBinFile(
            "DAPI   GFP   RFP",
            "Blue  Green  Red",
            "default  500  1000",
            "100-Infinity  50-5000  200-10000"
        );
        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(3, cfg.numChannels());
        assertEquals("RFP", cfg.channelNames.get(2));
        assertEquals("Red", cfg.channelColors.get(2));
    }

    @Test
    public void tabDelimitedFile_preservesChannelNameWithSpaces() throws IOException {
        // Write a tab-delimited file directly so channel names can contain spaces.
        File dir = tempFolder.newFolder("tabRead");
        File bin = new File(dir, ".bin");
        bin.mkdirs();
        File channelData = new File(bin, "Channel_Data.txt");
        Files.write(channelData.toPath(),
                ("DAPI\tIba1 nuclear\tGFP\n"
                        + "Blue\tGreen\tRed\n"
                        + "default\t500\t1000\n"
                        + "100-Infinity\t50-5000\t200-10000\n").getBytes(StandardCharsets.UTF_8));

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(3, cfg.numChannels());
        assertEquals("DAPI", cfg.channelNames.get(0));
        assertEquals("Iba1 nuclear", cfg.channelNames.get(1));
        assertEquals("GFP", cfg.channelNames.get(2));
    }

    @Test
    public void writeFromConfig_roundTripsChannelNameWithSpaces() throws IOException {
        File dir = tempFolder.newFolder("roundTripSpaces");
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("Iba1 nuclear");
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("default");
        cfg.channelThresholds.add("500");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelSizes.add("50-5000");
        cfg.channelMinMax.add("None");
        cfg.channelMinMax.add("0-4095");
        cfg.channelIntensityThresholds.add("default");
        cfg.channelIntensityThresholds.add("750");
        cfg.segmentationMethods.add("classical");
        cfg.segmentationMethods.add("classical");
        cfg.channelFilterPresets.add("Default");
        cfg.channelFilterPresets.add("Default");

        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);
        BinConfig back = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(java.util.Arrays.asList("DAPI", "Iba1 nuclear"), back.channelNames);
        assertEquals(java.util.Arrays.asList("default", "500"), back.channelThresholds);
    }

    @Test
    public void writeFromConfig_writesTabDelimitedLines() throws IOException {
        File dir = tempFolder.newFolder("tabWrite");
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("Iba1 nuclear");
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("default");
        cfg.channelThresholds.add("500");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelSizes.add("50-5000");

        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);

        java.util.List<String> lines = Files.readAllLines(
                configurationFile(dir, "Channel_Data.txt").toPath(),
                StandardCharsets.UTF_8);
        assertEquals("DAPI\tIba1 nuclear", lines.get(0));
        assertEquals("Blue\tGreen", lines.get(1));
        assertEquals("default\t500", lines.get(2));
        assertEquals("100-Infinity\t50-5000", lines.get(3));
    }

    @Test
    public void readFromDirectory_leavesLegacyWhitespaceFileUntouched() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP RFP",
            "Blue Green Red",
            "default 500 1000",
            "100-Infinity 50-5000 200-10000",
            "None 0-4095 100-3000",
            "default 750 default",
            "classical classical classical"
        );
        File channelData = new File(new File(dir, ".bin"), "Channel_Data.txt");
        // Sanity: original file is space-delimited.
        String before = new String(Files.readAllBytes(channelData.toPath()), StandardCharsets.UTF_8);
        assertFalse("legacy file should have no tabs before read", before.contains("\t"));

        BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        String after = new String(Files.readAllBytes(channelData.toPath()), StandardCharsets.UTF_8);
        assertEquals("legacy file should not be rewritten by a read", before, after);
        assertFalse(configurationFile(dir, "Channel_Data.txt").exists());
    }

    @Test
    public void writeFromConfig_writesChannelDataToConfigurationFolderOnly() throws IOException {
        File dir = tempFolder.newFolder("newConfigWrite");
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("GFP");
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("default");
        cfg.channelThresholds.add("500");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelSizes.add("50-5000");

        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);

        File newChannelData = configurationFile(dir, "Channel_Data.txt");
        assertTrue(newChannelData.isFile());
        assertFalse(new File(new File(dir, ".bin"), "Channel_Data.txt").exists());
        assertTrue(readFile(newChannelData).startsWith("DAPI\tGFP"));
    }

    @Test
    public void readFromDirectory_prefersConfigurationFolderOverLegacyBin() throws IOException {
        File dir = writeBinFile(
                "LEGACY",
                "Red",
                "default",
                "100-Infinity");
        File configDir = configurationDir(dir);
        assertTrue(configDir.mkdirs());
        Files.write(new File(configDir, "Channel_Data.txt").toPath(),
                ("NEW\n"
                        + "Blue\n"
                        + "default\n"
                        + "100-Infinity\n").getBytes(StandardCharsets.UTF_8));

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals("NEW", cfg.channelNames.get(0));
    }

    @Test
    public void readFromDirectory_stripsUtf8BomFromFirstChannelName() throws IOException {
        File dir = tempFolder.newFolder("bom");
        File bin = new File(dir, ".bin");
        bin.mkdirs();
        File channelData = new File(bin, "Channel_Data.txt");
        Files.write(channelData.toPath(),
                ("﻿DAPI\tGFP\n"
                        + "Blue\tGreen\n"
                        + "default\t500\n"
                        + "100-Infinity\t50-5000\n").getBytes(StandardCharsets.UTF_8));

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(2, cfg.numChannels());
        assertEquals("DAPI", cfg.channelNames.get(0));
    }

    @Test
    public void readPartialFromDirectory_stripsUtf8BomFromFirstChannelName() throws IOException {
        File dir = tempFolder.newFolder("bom-partial");
        File bin = new File(dir, ".bin");
        bin.mkdirs();
        File channelData = new File(bin, "Channel_Data.txt");
        Files.write(channelData.toPath(),
                "﻿DAPI\tGFP\n".getBytes(StandardCharsets.UTF_8));

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(2, cfg.numChannels());
        assertEquals("DAPI", cfg.channelNames.get(0));
    }

    @Test
    public void writeAtomic_writesTargetFileWithoutLeavingTmp() throws IOException {
        File dir = tempFolder.newFolder("atomicWrite");
        File target = new File(dir, "atomic_test.txt");
        BinConfigIO.writeAtomic(target.toPath(),
                java.util.Arrays.asList("line one", "line two"));

        assertTrue(target.isFile());
        assertEquals("line one\nline two\n",
                new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(dir, "atomic_test.txt.tmp").exists());
    }

    @Test
    public void mismatchedArrayLengths_paddingFillsDefaults() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP RFP",
            "Blue Green",
            "default",
            "100-Infinity"
        );
        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(3, cfg.numChannels());
        assertEquals("Grays", cfg.channelColors.get(2));
        assertEquals("default", cfg.channelThresholds.get(1));
        assertEquals("100-Infinity", cfg.channelSizes.get(2));
    }

    @Test
    public void updateMinMax_preservesOtherLines() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP",
            "Blue Green",
            "default 500",
            "100-Infinity 50-5000",
            "None None",
            "default default",
            "classical classical"
        );
        BinConfigIO.updateMinMax(dir.getAbsolutePath(), new String[]{"10-200", "50-4000"});

        // Re-read and verify
        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals("10-200", cfg.channelMinMax.get(0));
        assertEquals("50-4000", cfg.channelMinMax.get(1));
        // Other lines preserved
        assertEquals("DAPI", cfg.channelNames.get(0));
        assertEquals("500", cfg.channelThresholds.get(1));
        assertEquals("classical", cfg.segmentationMethods.get(0));
    }

    @Test
    public void updateMinMax_withNullElements() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP",
            "Blue Green",
            "default 500",
            "100-Infinity 50-5000",
            "None None"
        );
        BinConfigIO.updateMinMax(dir.getAbsolutePath(), new String[]{null, "50-4000"});

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals("None", cfg.channelMinMax.get(0));
        assertEquals("50-4000", cfg.channelMinMax.get(1));
    }

    @Test
    public void emptyChannelNamesLine() throws IOException {
        File dir = writeBinFile(
            "",
            "Blue Green",
            "default 500",
            "100-Infinity 50-5000"
        );
        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(0, cfg.numChannels());
    }

    @Test
    public void roundTrip_writeReadVerifyIdentical() throws IOException {
        File dir = writeBinFile(
            "DAPI GFP",
            "Blue Green",
            "default 500",
            "100-Infinity 50-5000",
            "None 0-4095",
            "default 750",
            "classical stardist:0.5:0.4"
        );

        BinConfig cfg1 = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        // Update min-max and re-read
        BinConfigIO.updateMinMax(dir.getAbsolutePath(),
            new String[]{cfg1.channelMinMax.get(0), cfg1.channelMinMax.get(1)});
        BinConfig cfg2 = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(cfg1.numChannels(), cfg2.numChannels());
        for (int i = 0; i < cfg1.numChannels(); i++) {
            assertEquals(cfg1.channelNames.get(i), cfg2.channelNames.get(i));
            assertEquals(cfg1.channelColors.get(i), cfg2.channelColors.get(i));
            assertEquals(cfg1.channelThresholds.get(i), cfg2.channelThresholds.get(i));
            assertEquals(cfg1.channelSizes.get(i), cfg2.channelSizes.get(i));
            assertEquals(cfg1.channelMinMax.get(i), cfg2.channelMinMax.get(i));
            assertEquals(cfg1.channelIntensityThresholds.get(i), cfg2.channelIntensityThresholds.get(i));
            assertEquals(cfg1.segmentationMethods.get(i), cfg2.segmentationMethods.get(i));
        }
    }
}
