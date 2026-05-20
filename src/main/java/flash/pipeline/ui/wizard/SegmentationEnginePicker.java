package flash.pipeline.ui.wizard;

import ij.IJ;

import java.util.Locale;

/**
 * Chooses a segmentation engine from marker shape, crowding, and runtime availability.
 */
public final class SegmentationEnginePicker {

    public enum Engine {
        Classical,
        StarDist,
        Cellpose
    }

    public static final class EngineAvailability {
        private final boolean starDistAvailable;
        private final boolean cellposeAvailable;

        public EngineAvailability(boolean starDistAvailable, boolean cellposeAvailable) {
            this.starDistAvailable = starDistAvailable;
            this.cellposeAvailable = cellposeAvailable;
        }

        public boolean isStarDistAvailable() {
            return starDistAvailable;
        }

        public boolean isCellposeAvailable() {
            return cellposeAvailable;
        }
    }

    public interface WarningSink {
        void warn(String message);
    }

    public static final class Recommendation {
        private final Engine engine;
        private final String warning;

        private Recommendation(Engine engine, String warning) {
            this.engine = engine;
            this.warning = warning;
        }

        public Engine engine() {
            return engine;
        }

        public String warning() {
            return warning;
        }
    }

    public static final WarningSink IJ_WARNING_SINK = new WarningSink() {
        @Override
        public void warn(String message) {
            IJ.log("WARNING: " + message);
        }
    };

    private SegmentationEnginePicker() {
    }

    public static Engine pick(MarkerLibrary.Entry entry,
                              String crowdingAnswer,
                              EngineAvailability availability) {
        if (entry == null) {
            return Engine.Classical;
        }
        return pick(entry.getShape(), entry.isCrowdingSensitive(), crowdingAnswer,
                availability, entry.isCrowdedByDefault(), IJ_WARNING_SINK);
    }

    public static Engine pick(String shape,
                              boolean crowdingSensitive,
                              String crowdingAnswer,
                              EngineAvailability availability) {
        return pick(shape, crowdingSensitive, crowdingAnswer, availability, false, IJ_WARNING_SINK);
    }

    public static Recommendation pickWithWarning(String shape,
                                                 boolean crowdingSensitive,
                                                 String crowdingAnswer,
                                                 boolean crowdedByDefault,
                                                 EngineAvailability availability) {
        final String[] warning = new String[]{null};
        Engine engine = pick(shape, crowdingSensitive, crowdingAnswer, availability, crowdedByDefault,
                new WarningSink() {
                    @Override
                    public void warn(String message) {
                        warning[0] = message;
                        IJ_WARNING_SINK.warn(message);
                    }
                });
        return new Recommendation(engine, warning[0]);
    }

    public static Recommendation pickWithWarning(MarkerLibrary.Entry entry,
                                                 String crowdingAnswer,
                                                 EngineAvailability availability) {
        if (entry == null) {
            return new Recommendation(Engine.Classical, null);
        }
        return pickWithWarning(entry.getShape(), entry.isCrowdingSensitive(), crowdingAnswer,
                entry.isCrowdedByDefault(), availability);
    }

    public static Engine pick(String shape,
                              boolean crowdingSensitive,
                              String crowdingAnswer,
                              EngineAvailability availability,
                              boolean crowdedByDefault,
                              WarningSink warningSink) {
        EngineAvailability safeAvailability = availability == null
                ? new EngineAvailability(false, false)
                : availability;
        WarningSink sink = warningSink == null ? new WarningSink() {
            @Override public void warn(String message) {}
        } : warningSink;

        String normalizedShape = normalize(shape);
        String answer = normalize(crowdingAnswer);
        if ("puncta_like".equals(normalizedShape) || "punctalike".equals(normalizedShape)
                || "diffuse".equals(normalizedShape)) {
            return Engine.Classical;
        }
        if (!crowdingSensitive) {
            return Engine.Classical;
        }
        if (answer.length() == 0) {
            if (!crowdedByDefault) {
                return Engine.Classical;
            }
            return specialisedForCrowded(normalizedShape, safeAvailability, sink);
        }
        if ("sparse".equals(answer)) {
            return Engine.Classical;
        }
        if (!"crowded".equals(answer)) {
            return Engine.Classical;
        }
        return specialisedForCrowded(normalizedShape, safeAvailability, sink);
    }

    private static Engine specialisedForCrowded(String shape,
                                                EngineAvailability availability,
                                                WarningSink sink) {
        if ("round".equals(shape)) {
            if (availability.isStarDistAvailable()) {
                return Engine.StarDist;
            }
            sink.warn("StarDist is unavailable; falling back to classical segmentation.");
            return Engine.Classical;
        }
        if ("complex".equals(shape)) {
            if (availability.isCellposeAvailable()) {
                return Engine.Cellpose;
            }
            if (availability.isStarDistAvailable()) {
                sink.warn("Cellpose is unavailable; using StarDist, which may not segment ramified cells well.");
                return Engine.StarDist;
            }
            sink.warn("Cellpose and StarDist are unavailable; falling back to classical segmentation.");
            return Engine.Classical;
        }
        return Engine.Classical;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
