package flash.pipeline.audit;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.zslice.ZSliceMode;
import org.junit.Test;

import static org.junit.Assert.*;

public class ReplayCommandFormatterTest {

    @Test
    public void formatsSingleChannelIntensityReplayWithParseableCliOptions() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelIntensityThresholds.add("default");
        cfg.zSliceMode = ZSliceMode.FULL;
        cfg.zSliceConfigPresent = true;

        String command = ReplayCommandFormatter.format("C:/data/Experiment One", 7, cfg);
        String options = extractOptions(command);
        CLIConfig parsed = CLIArgumentParser.parse(options);

        assertTrue(command.startsWith("IJ.run(\"FLASH - The Pipeline"));
        assertTrue(options.contains("run_intensity"));
        assertEquals("C:/data/Experiment One", parsed.getDirectory());
        assertTrue(parsed.getSelectedAnalyses()[7]);
        assertEquals("DAPI", parsed.getBinFieldValue(BinField.CHANNEL_NAMES));
        assertEquals("default", parsed.getBinFieldValue(BinField.INTENSITY_THRESHOLDS));
        assertEquals("full", parsed.getBinFieldValue(BinField.Z_SLICE));
    }

    @Test
    public void formatsMultiChannelObjectReplayWithCliSafeFilterPresetTokens() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("Iba1");
        cfg.channelColors.add("Cyan");
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("default");
        cfg.channelThresholds.add("1500");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelSizes.add("200-1000");
        cfg.segmentationMethods.add("classical");
        cfg.segmentationMethods.add("cellpose:30:nuclei");
        cfg.channelFilterPresets.add("Default");
        cfg.channelFilterPresets.add("Ramified Cells (Microglia/Astrocytes)");
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.zSliceConfigPresent = true;

        String options = extractOptions(ReplayCommandFormatter.format("C:/data/no_space", 4, cfg));
        CLIConfig parsed = CLIArgumentParser.parse(options);

        assertTrue(options.contains("run_3d"));
        assertTrue(parsed.getSelectedAnalyses()[4]);
        assertEquals("DAPI,Iba1", parsed.getBinFieldValue(BinField.CHANNEL_NAMES));
        assertEquals("Cyan,Green", parsed.getBinFieldValue(BinField.CHANNEL_COLORS));
        assertEquals("default,1500", parsed.getBinFieldValue(BinField.OBJECT_THRESHOLDS));
        assertEquals("100-Infinity,200-1000", parsed.getBinFieldValue(BinField.PARTICLE_SIZES));
        assertEquals("classical,cellpose:30:nuclei",
                parsed.getBinFieldValue(BinField.SEGMENTATION_METHODS));
        assertEquals("default,ramified_cells_microglia_astrocytes",
                parsed.getBinFieldValue(BinField.FILTER_PRESETS));
        assertEquals("per_image", parsed.getBinFieldValue(BinField.Z_SLICE));
    }

    @Test
    public void omitsZSliceModeWhenNoZSliceConfigWasResolved() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");

        String options = extractOptions(ReplayCommandFormatter.format("C:/data", 1, cfg));

        assertTrue(options.contains("run_roi"));
        assertFalse(options.contains("z_slice_mode="));
    }

    private static String extractOptions(String command) {
        int start = command.indexOf("\", \"");
        assertTrue("Expected IJ.run command with options string", start >= 0);
        start += 4;
        int end = command.lastIndexOf("\");");
        assertTrue("Expected IJ.run command terminator", end > start);
        return command.substring(start, end);
    }
}
