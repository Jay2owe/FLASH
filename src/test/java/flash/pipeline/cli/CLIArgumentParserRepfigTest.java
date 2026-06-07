package flash.pipeline.cli;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Test;

import java.awt.Color;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@code repfig.*} macro parsing, {@link CLIConfig.RepfigConfig}
 * {@code hasConfiguration()}/{@code applyTo}, and serialization round-trips.
 */
public class CLIArgumentParserRepfigTest {

    private static CLIConfig.RepfigConfig parseRepfig(String repfigOptions) {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] " + repfigOptions);
        assertNotNull(cfg);
        return cfg.getRepfig();
    }

    @Test
    public void noRepfigOptionsLeavesConfigEmpty() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp]");
        assertNotNull(cfg);
        assertFalse(cfg.getRepfig().hasConfiguration());
        assertFalse(cfg.getSelectedAnalyses()[12]);
    }

    @Test
    public void intOptionsParseAndClamp() {
        CLIConfig.RepfigConfig repfig = parseRepfig(
                "repfig.cell_size=400 repfig.row_gap=10 repfig.column_gap=20 "
                        + "repfig.inner_gap=3 repfig.margin=12 repfig.condition_font=14 "
                        + "repfig.channel_font=20 repfig.export_scale=3");
        assertEquals(Integer.valueOf(400), repfig.getCellSizePx());
        assertEquals(Integer.valueOf(10), repfig.getRowGapPx());
        assertEquals(Integer.valueOf(20), repfig.getConditionGapPx());
        assertEquals(Integer.valueOf(3), repfig.getInnerColGapPx());
        assertEquals(Integer.valueOf(12), repfig.getMarginPx());
        assertEquals(Integer.valueOf(14), repfig.getConditionFontSizePx());
        assertEquals(Integer.valueOf(20), repfig.getChannelFontSizePx());
        assertEquals(Integer.valueOf(3), repfig.getExportScale());
    }

    @Test
    public void cellSizeIsClampedToValidRange() {
        assertEquals(Integer.valueOf(1200), parseRepfig("repfig.cell_size=99999").getCellSizePx());
        assertEquals(Integer.valueOf(80), parseRepfig("repfig.cell_size=1").getCellSizePx());
    }

    @Test
    public void exportScaleIsClamped() {
        assertEquals(Integer.valueOf(4), parseRepfig("repfig.export_scale=9").getExportScale());
        assertEquals(Integer.valueOf(1), parseRepfig("repfig.export_scale=0").getExportScale());
    }

    @Test
    public void scaleBarOptionsParse() {
        CLIConfig.RepfigConfig repfig = parseRepfig(
                "repfig.scalebar=false repfig.scalebar_um=50 repfig.scalebar_thickness=12");
        assertEquals(Boolean.FALSE, repfig.getScaleBarEnabled());
        assertEquals(50.0, repfig.getScaleBarLengthUm().doubleValue(), 1e-9);
        assertEquals(Integer.valueOf(12), repfig.getScaleBarThicknessPx());
    }

    @Test
    public void scaleBarPositionShortAliasesParse() {
        assertEquals(PresentationTileConfig.Position.TOP_LEFT,
                parseRepfig("repfig.scalebar_position=tl").getScaleBarPosition());
        assertEquals(PresentationTileConfig.Position.TOP_RIGHT,
                parseRepfig("repfig.scalebar_position=tr").getScaleBarPosition());
        assertEquals(PresentationTileConfig.Position.BOTTOM_LEFT,
                parseRepfig("repfig.scalebar_position=bl").getScaleBarPosition());
        assertEquals(PresentationTileConfig.Position.BOTTOM_RIGHT,
                parseRepfig("repfig.scalebar_position=br").getScaleBarPosition());
    }

    @Test
    public void scaleBarPositionLongAliasesParse() {
        assertEquals(PresentationTileConfig.Position.TOP_LEFT,
                parseRepfig("repfig.scalebar_position=top_left").getScaleBarPosition());
        assertEquals(PresentationTileConfig.Position.BOTTOM_RIGHT,
                parseRepfig("repfig.scalebar_position=bottom_right").getScaleBarPosition());
    }

    @Test
    public void scaleBarFracParsesXandY() {
        CLIConfig.RepfigConfig repfig = parseRepfig("repfig.scalebar_frac=0.9,0.8");
        assertEquals(0.9, repfig.getScaleBarFracX().doubleValue(), 1e-9);
        assertEquals(0.8, repfig.getScaleBarFracY().doubleValue(), 1e-9);
    }

    @Test
    public void invalidScaleBarFracIsIgnored() {
        CLIConfig.RepfigConfig repfig = parseRepfig("repfig.scalebar_frac=0.9");
        assertNull(repfig.getScaleBarFracX());
        assertNull(repfig.getScaleBarFracY());
    }

    @Test
    public void labelModeAliasesParse() {
        assertEquals(PresentationTileConfig.LabelMode.NONE,
                parseRepfig("repfig.label_mode=none").getLabelMode());
        assertEquals(PresentationTileConfig.LabelMode.STAIN_NAME,
                parseRepfig("repfig.label_mode=stain").getLabelMode());
        assertEquals(PresentationTileConfig.LabelMode.IMAGE_NAME,
                parseRepfig("repfig.label_mode=image").getLabelMode());
        assertEquals(PresentationTileConfig.LabelMode.CONDITION_IMAGE,
                parseRepfig("repfig.label_mode=condition_image").getLabelMode());
        assertEquals(PresentationTileConfig.LabelMode.CUSTOM,
                parseRepfig("repfig.label_mode=custom").getLabelMode());
    }

    @Test
    public void labelTextAndFontAndPositionParse() {
        CLIConfig.RepfigConfig repfig = parseRepfig(
                "repfig.label_text=[{stain} ({condition})] repfig.label_font=24 "
                        + "repfig.label_position=tr repfig.label_frac=0.05,0.1");
        assertEquals("{stain} ({condition})", repfig.getCustomLabelTemplate());
        assertEquals(Integer.valueOf(24), repfig.getLabelFontSizePx());
        assertEquals(PresentationTileConfig.Position.TOP_RIGHT, repfig.getLabelPosition());
        assertEquals(0.05, repfig.getLabelFracX().doubleValue(), 1e-9);
        assertEquals(0.1, repfig.getLabelFracY().doubleValue(), 1e-9);
    }

    @Test
    public void colorAliasesParse() {
        assertEquals(Color.WHITE, parseRepfig("repfig.color=white").getAnnotationColor());
        assertEquals(Color.BLACK, parseRepfig("repfig.color=black").getAnnotationColor());
    }

    @Test
    public void unknownColorIsIgnored() {
        assertNull(parseRepfig("repfig.color=purple").getAnnotationColor());
    }

    @Test
    public void annotateTogglesAndGroupByParse() {
        CLIConfig.RepfigConfig repfig = parseRepfig(
                "repfig.annotate_tile=false repfig.annotate_individual=true "
                        + "repfig.group_by=condition");
        assertEquals(Boolean.FALSE, repfig.getAnnotateOverviewTile());
        assertEquals(Boolean.TRUE, repfig.getAnnotateIndividualImages());
        assertEquals(PresentationTileConfig.GroupRowsBy.CONDITION, repfig.getGroupRowsBy());
        assertEquals(PresentationTileConfig.GroupRowsBy.ANIMAL,
                parseRepfig("repfig.group_by=animal").getGroupRowsBy());
    }

    @Test
    public void channelOrderParsesCommaList() {
        CLIConfig.RepfigConfig repfig = parseRepfig("repfig.channel_order=[DAPI,GFP,Iba1]");
        assertEquals(Arrays.asList("DAPI", "GFP", "Iba1"), repfig.getChannelOrder());
    }

    @Test
    public void rowsParse() {
        assertEquals(Integer.valueOf(2), parseRepfig("repfig.rows=2").getRows());
    }

    @Test
    public void anyRepfigOptionAutoSelectsAnalysis12() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] repfig.cell_size=300");
        assertNotNull(cfg);
        assertTrue(cfg.getSelectedAnalyses()[12]);
    }

    @Test
    public void runRepfigFlagSelectsAnalysis12() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] run_repfig");
        assertNotNull(cfg);
        assertTrue(cfg.getSelectedAnalyses()[12]);
    }

    @Test
    public void hasConfigurationTrueWhenAnyFieldSet() {
        assertTrue(parseRepfig("repfig.scalebar=true").hasConfiguration());
    }

    @Test
    public void applyToOverridesOnlySetFields() {
        CLIConfig.RepfigConfig repfig = parseRepfig(
                "repfig.cell_size=500 repfig.scalebar=false repfig.label_mode=image "
                        + "repfig.label_position=br repfig.color=black "
                        + "repfig.group_by=condition repfig.label_text=[hi]");

        PresentationTileConfig base = PresentationTileConfig.builder()
                .cellSizePx(260)
                .scaleBarEnabled(true)
                .scaleBarLengthUm(123.0)
                .scaleBarThicknessPx(7)
                .labelMode(PresentationTileConfig.LabelMode.STAIN_NAME)
                .labelPosition(PresentationTileConfig.Position.TOP_LEFT)
                .labelFontSizePx(19)
                .annotationColor(Color.WHITE)
                .groupRowsBy(PresentationTileConfig.GroupRowsBy.ANIMAL)
                .build();

        PresentationTileConfig applied = repfig.applyTo(base);

        // Overridden fields.
        assertEquals(500, applied.cellSizePx());
        assertFalse(applied.scaleBarEnabled());
        assertEquals(PresentationTileConfig.LabelMode.IMAGE_NAME, applied.labelMode());
        assertEquals(PresentationTileConfig.Position.BOTTOM_RIGHT, applied.labelPosition());
        assertEquals(Color.BLACK, applied.annotationColor());
        assertEquals(PresentationTileConfig.GroupRowsBy.CONDITION, applied.groupRowsBy());
        assertEquals("hi", applied.customLabelTemplate());

        // Untouched fields preserved from base.
        assertEquals(123.0, applied.scaleBarLengthUm(), 1e-9);
        assertEquals(7, applied.scaleBarThicknessPx());
        assertEquals(19, applied.labelFontSizePx());
    }

    @Test
    public void applyToWithNoOverridesPreservesBase() {
        CLIConfig.RepfigConfig empty = new CLIConfig.RepfigConfig();
        PresentationTileConfig base = PresentationTileConfig.builder()
                .cellSizePx(333)
                .scaleBarLengthUm(77.0)
                .labelMode(PresentationTileConfig.LabelMode.CUSTOM)
                .build();
        PresentationTileConfig applied = empty.applyTo(base);
        assertEquals(333, applied.cellSizePx());
        assertEquals(77.0, applied.scaleBarLengthUm(), 1e-9);
        assertEquals(PresentationTileConfig.LabelMode.CUSTOM, applied.labelMode());
    }

    @Test
    public void applyToHandlesNullBase() {
        CLIConfig.RepfigConfig repfig = parseRepfig("repfig.cell_size=600");
        PresentationTileConfig applied = repfig.applyTo(null);
        assertNotNull(applied);
        assertEquals(600, applied.cellSizePx());
    }

    @Test
    public void applyToForwardsSpacingFontsExportScaleAndFractions() {
        CLIConfig.RepfigConfig repfig = parseRepfig(
                "repfig.row_gap=21 repfig.column_gap=22 repfig.inner_gap=7 repfig.margin=9 "
                        + "repfig.condition_font=20 repfig.channel_font=24 repfig.export_scale=3 "
                        + "repfig.label_frac=0.1,0.2 repfig.scalebar_frac=0.7,0.8");
        PresentationTileConfig applied = repfig.applyTo(
                PresentationTileConfig.builder().build());
        assertEquals(21, applied.rowGapPx());
        assertEquals(22, applied.conditionGapPx());
        assertEquals(7, applied.innerColGapPx());
        assertEquals(9, applied.marginPx());
        assertEquals(20, applied.conditionFontSizePx());
        assertEquals(24, applied.channelFontSizePx());
        assertEquals(3, applied.exportScale());
        assertEquals(0.1, applied.labelFracX(), 1e-9);
        assertEquals(0.2, applied.labelFracY(), 1e-9);
        assertEquals(0.7, applied.scaleBarFracX(), 1e-9);
        assertEquals(0.8, applied.scaleBarFracY(), 1e-9);
    }

    @Test
    public void serializeRoundTripIsStable() {
        String options = "dir=[/tmp] repfig.cell_size=420 repfig.row_gap=9 repfig.column_gap=14 "
                + "repfig.inner_gap=2 repfig.margin=10 repfig.condition_font=13 "
                + "repfig.channel_font=17 repfig.export_scale=2 repfig.scalebar=false "
                + "repfig.scalebar_um=75 repfig.scalebar_thickness=8 "
                + "repfig.scalebar_position=tl repfig.scalebar_frac=0.9,0.85 "
                + "repfig.label_mode=condition_image repfig.label_text=[{stain}] "
                + "repfig.label_font=22 repfig.label_position=br repfig.label_frac=0.05,0.06 "
                + "repfig.color=black repfig.annotate_tile=false repfig.annotate_individual=true "
                + "repfig.group_by=condition repfig.channel_order=[DAPI,GFP] repfig.rows=2";

        CLIConfig first = CLIArgumentParser.parse(options);
        assertNotNull(first);
        String serialized = CLIArgumentParser.serialize(first);
        CLIConfig second = CLIArgumentParser.parse("dir=[/tmp] " + serialized);
        assertNotNull(second);

        CLIConfig.RepfigConfig a = first.getRepfig();
        CLIConfig.RepfigConfig b = second.getRepfig();

        assertEquals(a.getCellSizePx(), b.getCellSizePx());
        assertEquals(a.getRowGapPx(), b.getRowGapPx());
        assertEquals(a.getConditionGapPx(), b.getConditionGapPx());
        assertEquals(a.getInnerColGapPx(), b.getInnerColGapPx());
        assertEquals(a.getMarginPx(), b.getMarginPx());
        assertEquals(a.getConditionFontSizePx(), b.getConditionFontSizePx());
        assertEquals(a.getChannelFontSizePx(), b.getChannelFontSizePx());
        assertEquals(a.getExportScale(), b.getExportScale());
        assertEquals(a.getScaleBarEnabled(), b.getScaleBarEnabled());
        assertEquals(a.getScaleBarLengthUm(), b.getScaleBarLengthUm());
        assertEquals(a.getScaleBarThicknessPx(), b.getScaleBarThicknessPx());
        assertEquals(a.getScaleBarPosition(), b.getScaleBarPosition());
        assertEquals(a.getScaleBarFracX(), b.getScaleBarFracX());
        assertEquals(a.getScaleBarFracY(), b.getScaleBarFracY());
        assertEquals(a.getLabelMode(), b.getLabelMode());
        assertEquals(a.getCustomLabelTemplate(), b.getCustomLabelTemplate());
        assertEquals(a.getLabelFontSizePx(), b.getLabelFontSizePx());
        assertEquals(a.getLabelPosition(), b.getLabelPosition());
        assertEquals(a.getLabelFracX(), b.getLabelFracX());
        assertEquals(a.getLabelFracY(), b.getLabelFracY());
        assertEquals(a.getAnnotationColor(), b.getAnnotationColor());
        assertEquals(a.getAnnotateOverviewTile(), b.getAnnotateOverviewTile());
        assertEquals(a.getAnnotateIndividualImages(), b.getAnnotateIndividualImages());
        assertEquals(a.getGroupRowsBy(), b.getGroupRowsBy());
        assertEquals(a.getChannelOrder(), b.getChannelOrder());
        assertEquals(a.getRows(), b.getRows());
    }

    @Test
    public void serializeOmitsRepfigWhenUnset() {
        CLIConfig cfg = CLIArgumentParser.parse("dir=[/tmp] threads=2");
        assertNotNull(cfg);
        String serialized = CLIArgumentParser.serialize(cfg);
        assertFalse(serialized, serialized.contains("repfig."));
    }
}
