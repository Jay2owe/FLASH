package flash.pipeline.decontamination;

import java.util.Set;

/**
 * Metadata for one selectable Spectral Decontamination feature.
 */
public interface CorrectionFeature {

    enum InputType {
        SOURCE_IMAGE("Source image"),
        CORRECTED_IMAGE("Corrected image"),
        MASK("Mask"),
        VETO_MASK("Veto mask");

        private final String label;

        InputType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    enum OutputType {
        CORRECTED_IMAGE("Corrected image"),
        MASK("Mask"),
        VETO_MASK("Veto mask"),
        OBJECT_SCORE("Object score"),
        METRIC("Metric");

        private final String label;

        OutputType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    enum RequiredChannel {
        TARGET("target channel"),
        BLEED_THROUGH("bleed-through channel"),
        AUTOFLUORESCENCE("autofluorescence channel"),
        CONTAMINANT("bleed-through or autofluorescence channel");

        private final String label;

        RequiredChannel(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    String getId();

    String getDisplayName();

    String getDescription();

    InputType getRequiredInputType();

    OutputType getOutputType();

    Set<RequiredChannel> getRequiredChannels();

    boolean requiresConditions();

    boolean requiresControls();

    boolean requiresExistingObjectMaps();

    boolean canPreviewCheaply();

    boolean isExpertOnly();

    boolean isThresholdFeature();

    boolean requiresVetoMask();
}
