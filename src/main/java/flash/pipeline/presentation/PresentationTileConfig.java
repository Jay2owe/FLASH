package flash.pipeline.presentation;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * User-facing options for presentation overview tiles and optional annotated
 * individual image copies.
 */
public final class PresentationTileConfig {

    public enum GroupRowsBy {
        ANIMAL,
        CONDITION
    }

    public enum Position {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public enum LabelMode {
        NONE,
        STAIN_NAME,
        IMAGE_NAME,
        CONDITION_IMAGE,
        CUSTOM
    }

    private final boolean createOverviewTile;
    private final boolean annotateOverviewTile;
    private final boolean annotateIndividualImages;
    private final GroupRowsBy groupRowsBy;
    private final List<String> channelOrder;
    private final int cellSizePx;
    private final boolean scaleBarEnabled;
    private final double scaleBarLengthUm;
    private final int scaleBarThicknessPx;
    private final Position scaleBarPosition;
    private final Color annotationColor;
    private final LabelMode labelMode;
    private final String customLabelTemplate;
    private final int labelFontSizePx;
    private final Position labelPosition;

    private PresentationTileConfig(Builder b) {
        this.annotateIndividualImages = b.annotateIndividualImages;
        this.createOverviewTile = b.createOverviewTile || b.annotateIndividualImages;
        this.annotateOverviewTile = b.annotateOverviewTile || b.annotateIndividualImages;
        this.groupRowsBy = b.groupRowsBy == null ? GroupRowsBy.ANIMAL : b.groupRowsBy;
        this.channelOrder = Collections.unmodifiableList(new ArrayList<String>(b.channelOrder));
        this.cellSizePx = clamp(b.cellSizePx, 80, 1200);
        this.scaleBarEnabled = b.scaleBarEnabled;
        this.scaleBarLengthUm = b.scaleBarLengthUm > 0 ? b.scaleBarLengthUm : 100.0;
        this.scaleBarThicknessPx = clamp(b.scaleBarThicknessPx, 1, 30);
        this.scaleBarPosition = b.scaleBarPosition == null ? Position.BOTTOM_RIGHT : b.scaleBarPosition;
        this.annotationColor = b.annotationColor == null ? Color.WHITE : b.annotationColor;
        this.labelMode = b.labelMode == null ? LabelMode.STAIN_NAME : b.labelMode;
        this.customLabelTemplate = b.customLabelTemplate == null ? "" : b.customLabelTemplate.trim();
        this.labelFontSizePx = clamp(b.labelFontSizePx, 8, 96);
        this.labelPosition = b.labelPosition == null ? Position.TOP_LEFT : b.labelPosition;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PresentationTileConfig disabled(List<String> channelOrder) {
        return builder().createOverviewTile(false).channelOrder(channelOrder).build();
    }

    public boolean createOverviewTile() {
        return createOverviewTile;
    }

    public boolean annotateOverviewTile() {
        return annotateOverviewTile;
    }

    public boolean annotateIndividualImages() {
        return annotateIndividualImages;
    }

    public GroupRowsBy groupRowsBy() {
        return groupRowsBy;
    }

    public List<String> channelOrder() {
        return channelOrder;
    }

    public int cellSizePx() {
        return cellSizePx;
    }

    public boolean scaleBarEnabled() {
        return scaleBarEnabled;
    }

    public double scaleBarLengthUm() {
        return scaleBarLengthUm;
    }

    public int scaleBarThicknessPx() {
        return scaleBarThicknessPx;
    }

    public Position scaleBarPosition() {
        return scaleBarPosition;
    }

    public Color annotationColor() {
        return annotationColor;
    }

    public LabelMode labelMode() {
        return labelMode;
    }

    public String customLabelTemplate() {
        return customLabelTemplate;
    }

    public int labelFontSizePx() {
        return labelFontSizePx;
    }

    public Position labelPosition() {
        return labelPosition;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Builder {
        private boolean createOverviewTile;
        private boolean annotateOverviewTile = true;
        private boolean annotateIndividualImages;
        private GroupRowsBy groupRowsBy = GroupRowsBy.ANIMAL;
        private List<String> channelOrder = new ArrayList<String>();
        private int cellSizePx = 260;
        private boolean scaleBarEnabled = true;
        private double scaleBarLengthUm = 100.0;
        private int scaleBarThicknessPx = 6;
        private Position scaleBarPosition = Position.BOTTOM_RIGHT;
        private Color annotationColor = Color.WHITE;
        private LabelMode labelMode = LabelMode.STAIN_NAME;
        private String customLabelTemplate = "{stain}";
        private int labelFontSizePx = 18;
        private Position labelPosition = Position.TOP_LEFT;

        public Builder createOverviewTile(boolean value) {
            this.createOverviewTile = value;
            return this;
        }

        public Builder annotateOverviewTile(boolean value) {
            this.annotateOverviewTile = value;
            return this;
        }

        public Builder annotateIndividualImages(boolean value) {
            this.annotateIndividualImages = value;
            return this;
        }

        public Builder groupRowsBy(GroupRowsBy value) {
            this.groupRowsBy = value;
            return this;
        }

        public Builder channelOrder(List<String> values) {
            this.channelOrder = new ArrayList<String>();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.trim().isEmpty()) {
                        this.channelOrder.add(value.trim());
                    }
                }
            }
            return this;
        }

        public Builder cellSizePx(int value) {
            this.cellSizePx = value;
            return this;
        }

        public Builder scaleBarEnabled(boolean value) {
            this.scaleBarEnabled = value;
            return this;
        }

        public Builder scaleBarLengthUm(double value) {
            this.scaleBarLengthUm = value;
            return this;
        }

        public Builder scaleBarThicknessPx(int value) {
            this.scaleBarThicknessPx = value;
            return this;
        }

        public Builder scaleBarPosition(Position value) {
            this.scaleBarPosition = value;
            return this;
        }

        public Builder annotationColor(Color value) {
            this.annotationColor = value;
            return this;
        }

        public Builder labelMode(LabelMode value) {
            this.labelMode = value;
            return this;
        }

        public Builder customLabelTemplate(String value) {
            this.customLabelTemplate = value;
            return this;
        }

        public Builder labelFontSizePx(int value) {
            this.labelFontSizePx = value;
            return this;
        }

        public Builder labelPosition(Position value) {
            this.labelPosition = value;
            return this;
        }

        public PresentationTileConfig build() {
            return new PresentationTileConfig(this);
        }
    }
}
