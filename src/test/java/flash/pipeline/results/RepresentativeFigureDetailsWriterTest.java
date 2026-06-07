package flash.pipeline.results;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.representative.RepresentativeFigureConfig;
import flash.pipeline.representative.RepresentativeLayout;
import flash.pipeline.representative.RepresentativeSelection;
import flash.pipeline.representative.RepresentativeSeries;
import flash.pipeline.representative.RepresentativeStatistic;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class RepresentativeFigureDetailsWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writeRecordsSelectedSeriesAndAppliedRanges() throws Exception {
        File project = temp.newFolder("details-project");
        File source = new File(project, "source.lif");
        Files.write(source.toPath(), "source".getBytes(StandardCharsets.UTF_8));
        File output = new File(project, "figure.png");
        Files.write(output.toPath(), "png".getBytes(StandardCharsets.UTF_8));

        RepresentativeFigureConfig config = representativeConfig(source);
        BinConfig setup = new BinConfig();
        setup.channelNames.add("DAPI");
        setup.channelNames.add("GFAP");
        setup.channelMinMax.add("None");
        setup.channelMinMax.add("3-99");

        File out = RepresentativeFigureDetailsWriter.write(project, config, setup, output);

        assertTrue(out.isFile());
        assertTrue(out.getAbsolutePath().contains(
                "FLASH" + File.separator + "Results"
                        + File.separator + "Run Records"
                        + File.separator + "analysis_details"));
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("Condition: Control"));
        assertTrue(text.contains("Series ID: series-0001"));
        assertTrue(text.contains("Channel 1 (DAPI): 10-200 (representative custom)"));
        assertTrue(text.contains("Channel 2 (GFAP): 3-99 (setup display range)"));
        assertTrue(text.contains("Row 1: Control"));
        assertTrue(text.contains("Channel order: DAPI, GFAP, Merge"));

        File[] leftovers = out.getParentFile().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    private static RepresentativeFigureConfig representativeConfig(File source) {
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        config.statistic = RepresentativeStatistic.QUICK;
        RepresentativeSeries series = new RepresentativeSeries(
                "series-0001",
                0,
                1,
                "Exp-Mouse1_LH_SCN",
                "Mouse1",
                "Control",
                "LH",
                "SCN",
                source,
                Arrays.asList(
                        new RepresentativeSeries.ChannelThumbnail(0, "DAPI", null, null),
                        new RepresentativeSeries.ChannelThumbnail(1, "GFAP", null, null)),
                null,
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put("Control", series);
        config.selection = new RepresentativeSelection(
                Collections.singletonList("Control"), selected);
        config.setCustomDisplayRangeForChannel(0, "10-200");
        config.layout = RepresentativeLayout.allInOneRow(Collections.singletonList("Control"));
        config.tileConfig = PresentationTileConfig.builder()
                .createOverviewTile(true)
                .annotateOverviewTile(false)
                .scaleBarEnabled(false)
                .channelOrder(Arrays.asList("DAPI", "GFAP", "Merge"))
                .build();
        return config;
    }
}
