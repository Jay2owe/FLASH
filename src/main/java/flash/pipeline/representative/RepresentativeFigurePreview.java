package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Cheap, dependency-light composite of the representative figure for live
 * previews. Builds {@code RenderedFinalSeries} from the cached selection
 * thumbnails and reuses the exact final compositing path
 * ({@link RepresentativeFigureWriter#renderFigureImage}), then downscales the
 * result to a thumbnail so the on-screen preview never diverges from the PNG.
 */
public final class RepresentativeFigurePreview {

    /**
     * Source cell size cap before downscaling, to bound preview cost. Kept above
     * the default cell (260) so the common case renders at proportionally exact
     * geometry; only unusually large cells are capped.
     */
    private static final int PREVIEW_CELL_CAP = 320;

    private RepresentativeFigurePreview() {
    }

    /**
     * Render a downscaled thumbnail of the whole figure from cached thumbnails.
     * Returns {@code null} when the selection is incomplete or nothing renders.
     */
    public static BufferedImage renderLayoutThumbnail(RepresentativeSelection selection,
                                                      RepresentativeLayout layout,
                                                      PresentationTileConfig tileConfig,
                                                      int maxLongEdgePx) {
        if (selection == null || !selection.isComplete() || tileConfig == null) {
            return null;
        }
        RepresentativeLayout safeLayout = safeLayout(selection, layout);
        List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered =
                renderedFromSelection(selection, safeLayout);
        if (rendered.isEmpty()) {
            return null;
        }

        PresentationTileConfig previewTile = tileConfig.toBuilder()
                .exportScale(1)
                .cellSizePx(Math.min(tileConfig.cellSizePx(), PREVIEW_CELL_CAP))
                .build();

        RepresentativeFigureConfig previewConfig = new RepresentativeFigureConfig();
        previewConfig.selection = selection;
        previewConfig.layout = safeLayout;
        previewConfig.tileConfig = previewTile;

        BufferedImage figure;
        try {
            figure = RepresentativeFigureWriter.renderFigureImage(previewConfig, rendered);
        } catch (RuntimeException e) {
            return null;
        }
        return downscale(figure, maxLongEdgePx);
    }

    /**
     * Final output pixel size for the given layout and config (full cell and
     * export scale), independent of the downscaled preview.
     */
    public static Dimension finalFigureSize(RepresentativeSelection selection,
                                            RepresentativeLayout layout,
                                            PresentationTileConfig tileConfig) {
        if (selection == null || !selection.isComplete() || tileConfig == null) {
            return new Dimension(0, 0);
        }
        RepresentativeLayout safeLayout = safeLayout(selection, layout);
        List<RepresentativePreviewRenderer.RenderedFinalSeries> rendered =
                renderedFromSelection(selection, safeLayout);
        return RepresentativeFigureWriter.computeFigureSize(safeLayout, rendered, tileConfig);
    }

    private static RepresentativeLayout safeLayout(RepresentativeSelection selection,
                                                   RepresentativeLayout layout) {
        if (layout != null && layout.containsExactlyConditions(selection.conditionNames())) {
            return layout;
        }
        return RepresentativeLayout.allInOneRow(selection.conditionNames());
    }

    private static List<RepresentativePreviewRenderer.RenderedFinalSeries> renderedFromSelection(
            RepresentativeSelection selection, RepresentativeLayout layout) {
        List<RepresentativePreviewRenderer.RenderedFinalSeries> out =
                new ArrayList<RepresentativePreviewRenderer.RenderedFinalSeries>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String condition : layout.flattenedConditions()) {
            String key = RepresentativeSelection.conditionLabel(condition);
            if (!seen.add(key)) {
                continue;
            }
            RepresentativeSeries series = selection.seriesForCondition(condition);
            if (series == null) {
                continue;
            }
            out.add(toRenderedFinal(series));
        }
        return out;
    }

    private static RepresentativePreviewRenderer.RenderedFinalSeries toRenderedFinal(
            RepresentativeSeries series) {
        List<RepresentativePreviewRenderer.RenderedFinalChannel> channels =
                new ArrayList<RepresentativePreviewRenderer.RenderedFinalChannel>();
        for (RepresentativeSeries.ChannelThumbnail thumbnail : series.channelThumbnails()) {
            if (thumbnail == null) {
                continue;
            }
            channels.add(new RepresentativePreviewRenderer.RenderedFinalChannel(
                    thumbnail.channelIndex(), thumbnail.channelName(), "",
                    thumbnail.image()));
        }
        // pixelWidthUm = 0 keeps the scale bar out of the cached-thumbnail preview
        // (true microns are unknown here); the final render and tile editor draw it.
        return new RepresentativePreviewRenderer.RenderedFinalSeries(
                series.id(), series.seriesIndex(), series.seriesNumber(),
                series.seriesName(), series.animal(), series.condition(),
                series.hemisphere(), series.region(), series.sourcePath(),
                channels, series.mergeThumbnail(), 0.0, 0.0);
    }

    private static BufferedImage downscale(BufferedImage source, int maxLongEdgePx) {
        if (source == null) {
            return null;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        int longEdge = Math.max(w, h);
        if (maxLongEdgePx <= 0 || longEdge <= maxLongEdgePx) {
            return source;
        }
        double scale = maxLongEdgePx / (double) longEdge;
        int dw = Math.max(1, (int) Math.round(w * scale));
        int dh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage scaled = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, dw, dh, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }
}
