package flash.pipeline.integration;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.decontamination.SpectralOutputWriter;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlashOutputLayoutCompatibilityTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void configurationWritesToFlashAndLegacyBinStillReads() throws Exception {
        File project = temp.newFolder("project");
        BinConfig cfg = minimalConfig("DAPI");

        BinConfigIO.writeFromConfig(project.getAbsolutePath(), cfg);

        File newChannelData = new File(project,
                "FLASH/Set Up Configuration/.settings/Channel_Data.txt");
        assertTrue(newChannelData.isFile());
        assertFalse(new File(project, ".bin/Channel_Data.txt").exists());
        assertEquals("DAPI", BinConfigIO.readFromDirectory(
                project.getAbsolutePath()).channelNames.get(0));

        File legacyProject = temp.newFolder("legacy-project");
        File legacyBin = new File(legacyProject, ".bin");
        assertTrue(legacyBin.mkdirs());
        Files.write(new File(legacyBin, "Channel_Data.txt").toPath(),
                ("IBA1\nGreen\n100\n10-Infinity\nNone\ndefault\nclassical\nDefault\n")
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals("IBA1", BinConfigIO.readFromDirectory(
                legacyProject.getAbsolutePath()).channelNames.get(0));
    }

    @Test
    public void spectralWriterUsesResultsLayout() throws Exception {
        File project = temp.newFolder("project");

        SpectralOutputWriter.ExpectedOutputs outputs =
                SpectralOutputWriter.expectedOutputs(project.getAbsolutePath(),
                        0, "Sample_LH_SCN", "Target");
        assertEquals(new File(project,
                        "FLASH/Results/Analysis Images/Spectral Decontamination/"
                                + "Series 001 - Sample_LH_SCN/corrected_Target.tif"),
                outputs.correctedImageFile);
    }

    private static BinConfig minimalConfig(String channelName) {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add(channelName);
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("100");
        cfg.channelSizes.add("10-Infinity");
        cfg.channelMinMax.add("None");
        cfg.channelIntensityThresholds.add("default");
        cfg.segmentationMethods.add("classical");
        cfg.channelFilterPresets.add("Default");
        return cfg;
    }
}
