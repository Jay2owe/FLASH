package flash.pipeline.deconv.wizard;

import flash.pipeline.deconv.RefractiveIndexEstimator;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.ui.PipelineDialog;
import ij.IJ;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.awt.GraphicsEnvironment;
import java.util.Locale;

/**
 * Guided wizard that collapses expert deconvolution choices into 2-4 user questions.
 */
@SuppressWarnings("unchecked")
public final class ImageConsultantWizard {

    private static final String DIALOG_TITLE = "Image Consultant";

    private final MetadataDiagnostics.SeriesInfo seriesInfo;
    private final Availability availability;
    private final String currentMountingMedium;

    public ImageConsultantWizard(MetadataDiagnostics.SeriesInfo seriesInfo) {
        this(seriesInfo, Availability.fromCurrentRuntime(), null);
    }

    public ImageConsultantWizard(MetadataDiagnostics.SeriesInfo seriesInfo,
                                 Availability availability,
                                 String currentMountingMedium) {
        this.seriesInfo = seriesInfo;
        this.availability = availability == null ? Availability.fromCurrentRuntime() : availability;
        this.currentMountingMedium = emptyToNull(currentMountingMedium);
    }

    public Recommendation run() {
        if (GraphicsEnvironment.isHeadless() || IJ.getInstance() == null) {
            IJ.log(DIALOG_TITLE + ": wizard is unavailable in headless mode.");
            return null;
        }

        Priority priority = null;
        SampleDepth sampleDepth = null;
        ScopeModality modality = MetadataDiagnostics.guessScopeModality(seriesInfo);
        Double pinholeAU = Double.valueOf(1.0);
        String mountingMediumHint = currentMountingMedium;

        int step = 1;
        while (true) {
            switch (step) {
                case 1: {
                    ScreenResult<Priority> result = showPriorityScreen(priority);
                    if (result.canceled) return null;
                    priority = result.value;
                    step = 2;
                    break;
                }
                case 2: {
                    ScreenResult<SampleDepth> result = showSampleDepthScreen(sampleDepth);
                    if (result.canceled) return null;
                    if (result.back) {
                        step = 1;
                        break;
                    }
                    sampleDepth = result.value;
                    if (sampleDepth == SampleDepth.THIN) {
                        mountingMediumHint = null;
                    } else if (sampleDepth == SampleDepth.CLEARED && emptyToNull(mountingMediumHint) == null) {
                        mountingMediumHint = "clarity";
                    }

                    if (needsModalityQuestion(seriesInfo)) {
                        step = 3;
                    } else if (needsMountingMediumQuestion(sampleDepth, mountingMediumHint)) {
                        step = 4;
                    } else {
                        return deriveRecommendation(seriesInfo,
                                new Answers(priority, sampleDepth, modality, pinholeAU, mountingMediumHint),
                                availability);
                    }
                    break;
                }
                case 3: {
                    ModalityScreenResult result = showModalityScreen(modality, pinholeAU);
                    if (result.canceled) return null;
                    if (result.back) {
                        step = 2;
                        break;
                    }
                    modality = result.modality;
                    pinholeAU = result.pinholeAU;
                    if (needsMountingMediumQuestion(sampleDepth, mountingMediumHint)) {
                        step = 4;
                    } else {
                        return deriveRecommendation(seriesInfo,
                                new Answers(priority, sampleDepth, modality, pinholeAU, mountingMediumHint),
                                availability);
                    }
                    break;
                }
                case 4: {
                    ScreenResult<String> result = showMountingMediumScreen(sampleDepth, mountingMediumHint);
                    if (result.canceled) return null;
                    if (result.back) {
                        step = needsModalityQuestion(seriesInfo) ? 3 : 2;
                        break;
                    }
                    mountingMediumHint = result.value;
                    return deriveRecommendation(seriesInfo,
                            new Answers(priority, sampleDepth, modality, pinholeAU, mountingMediumHint),
                            availability);
                }
                default:
                    throw new IllegalStateException("Unknown wizard step " + step);
            }
        }
    }

    public static Recommendation deriveRecommendation(MetadataDiagnostics.SeriesInfo info,
                                                      Answers answers,
                                                      Availability availability) {
        if (answers == null) {
            throw new IllegalArgumentException("answers are required.");
        }
        if (availability == null) {
            throw new IllegalArgumentException("availability is required.");
        }

        ScopeModality modality = answers.getScopeModality();
        if (modality == null) {
            modality = MetadataDiagnostics.guessScopeModality(info);
        }
        if (modality == null) {
            modality = ScopeModality.WIDEFIELD;
        }

        String engineKey = chooseEngineKey(answers.getPriority(), availability);
        Algorithm algorithm = defaultAlgorithmForEngine(engineKey);
        PsfModel psfModel = answers.getSampleDepth() == SampleDepth.THIN
                ? PsfModel.BORN_WOLF
                : PsfModel.GIBSON_LANNI;

        String mountingMediumHint = resolvedMountingMediumHint(answers.getSampleDepth(), answers.getMountingMediumHint());
        double sampleRI;
        if (answers.getSampleDepth() == SampleDepth.THIN) {
            sampleRI = RefractiveIndexEstimator.inferSampleRI(info == null ? null : info.objectiveImmersion, null);
        } else {
            sampleRI = RefractiveIndexEstimator.mountingMediumRI(mountingMediumHint);
            if (Double.isNaN(sampleRI) || sampleRI <= 0.0) {
                sampleRI = RefractiveIndexEstimator.inferSampleRI(
                        info == null ? null : info.objectiveImmersion,
                        mountingMediumHint);
            }
            if (answers.getSampleDepth() == SampleDepth.CLEARED && (Double.isNaN(sampleRI) || sampleRI <= 0.0)) {
                sampleRI = RefractiveIndexEstimator.mountingMediumRI("clarity");
            }
        }

        IterationRecommendation tuning = deriveIterationRecommendation(
                answers.getPriority(),
                answers.getSampleDepth());

        Double pinhole = modality == ScopeModality.CONFOCAL
                ? positiveOrDefault(answers.getPinholeAU(), 1.0)
                : null;

        return new Recommendation(
                engineKey,
                algorithm,
                psfModel,
                modality,
                pinhole,
                Double.valueOf(sampleRI),
                mountingMediumHint,
                tuning.iterations,
                tuning.regularization
        );
    }

    static boolean needsModalityQuestion(MetadataDiagnostics.SeriesInfo info) {
        return MetadataDiagnostics.guessScopeModality(info) == null;
    }

    static boolean needsMountingMediumQuestion(SampleDepth sampleDepth, String currentMountingMediumHint) {
        if (sampleDepth == null || sampleDepth == SampleDepth.THIN) {
            return false;
        }
        String effectiveHint = resolvedMountingMediumHint(sampleDepth, currentMountingMediumHint);
        return Double.isNaN(RefractiveIndexEstimator.mountingMediumRI(effectiveHint));
    }

    static String chooseEngineKey(Priority priority, Availability availability) {
        if (priority == Priority.ACCURACY) {
            if (availability.isDl2Available()) return "DL2";
            if (availability.isClij2Available()) return "CLIJ2";
            if (availability.isIterativeAvailable()) return "IterativeDeconvolve3D";
        } else {
            if (availability.isClij2Available()) return "CLIJ2";
            if (availability.isIterativeAvailable()) return "IterativeDeconvolve3D";
            if (availability.isDl2Available()) return "DL2";
        }
        if (availability.isClij2Available()) return "CLIJ2";
        if (availability.isDl2Available()) return "DL2";
        if (availability.isIterativeAvailable()) return "IterativeDeconvolve3D";
        return priority == Priority.ACCURACY ? "DL2" : "CLIJ2";
    }

    static Algorithm defaultAlgorithmForEngine(String engineKey) {
        return "IterativeDeconvolve3D".equals(engineKey) ? Algorithm.RL : Algorithm.RL_TV;
    }

    static IterationRecommendation deriveIterationRecommendation(Priority priority, SampleDepth sampleDepth) {
        if (priority == null || sampleDepth == null) {
            throw new IllegalArgumentException("priority and sampleDepth are required.");
        }

        // Deeper samples converge more slowly, so they get more RL iterations.
        // Stronger TV regularization damps the noise amplification that comes with those extra iterations.
        if (priority == Priority.SPEED && sampleDepth == SampleDepth.THIN) {
            return new IterationRecommendation(10, 0.005);
        }
        if (priority == Priority.SPEED) {
            return new IterationRecommendation(15, 0.01);
        }
        if (sampleDepth == SampleDepth.THIN) {
            return new IterationRecommendation(15, 0.005);
        }
        if (sampleDepth == SampleDepth.DEEP) {
            return new IterationRecommendation(20, 0.02);
        }
        return new IterationRecommendation(20, 0.01);
    }

    private ScreenResult<Priority> showPriorityScreen(Priority current) {
        PipelineDialog dialog = new PipelineDialog(DIALOG_TITLE + " - Priority");
        dialog.addHeader("What matters more - speed or image quality?");
        JComboBox<String> choice = dialog.addChoice(
                "Priority",
                new String[]{
                        "Speed / large dataset",
                        "Maximum accuracy"
                },
                current == Priority.ACCURACY ? "Maximum accuracy" : "Speed / large dataset"
        );
        if (!dialog.showDialog()) {
            return ScreenResult.canceled();
        }
        String selected = String.valueOf(choice.getSelectedItem());
        return ScreenResult.of("Maximum accuracy".equals(selected) ? Priority.ACCURACY : Priority.SPEED);
    }

    private ScreenResult<SampleDepth> showSampleDepthScreen(SampleDepth current) {
        PipelineDialog dialog = new PipelineDialog(DIALOG_TITLE + " - Sample");
        dialog.enableBackButton();
        dialog.addHeader("What's your sample?");
        JComboBox<String> choice = dialog.addChoice(
                "Sample",
                new String[]{
                        "Thin section, near the coverslip",
                        "Deep in thick tissue",
                        "Cleared tissue (CLARITY / iDISCO / CUBIC / CE3D / etc.)"
                },
                current == SampleDepth.DEEP
                        ? "Deep in thick tissue"
                        : (current == SampleDepth.CLEARED
                        ? "Cleared tissue (CLARITY / iDISCO / CUBIC / CE3D / etc.)"
                        : "Thin section, near the coverslip")
        );
        if (!dialog.showDialog()) {
            return dialog.wasBackPressed() ? ScreenResult.back() : ScreenResult.canceled();
        }
        String selected = String.valueOf(choice.getSelectedItem());
        if (selected.startsWith("Deep")) {
            return ScreenResult.of(SampleDepth.DEEP);
        }
        if (selected.startsWith("Cleared")) {
            return ScreenResult.of(SampleDepth.CLEARED);
        }
        return ScreenResult.of(SampleDepth.THIN);
    }

    private ModalityScreenResult showModalityScreen(ScopeModality current, Double currentPinhole) {
        PipelineDialog dialog = new PipelineDialog(DIALOG_TITLE + " - Modality");
        dialog.enableBackButton();
        dialog.addHeader("Which microscope was used?");
        JComboBox<String> choice = dialog.addChoice(
                "Microscope",
                new String[]{"Widefield", "Confocal", "Spinning Disk"},
                current == ScopeModality.CONFOCAL
                        ? "Confocal"
                        : (current == ScopeModality.SPINNING_DISK ? "Spinning Disk" : "Widefield")
        );
        JTextField pinholeField = dialog.addNumericField(
                "Pinhole (Airy units)",
                positiveOrDefault(currentPinhole, 1.0),
                1
        );
        pinholeField.getParent().setVisible("Confocal".equals(choice.getSelectedItem()));
        choice.addActionListener(e ->
                pinholeField.getParent().setVisible("Confocal".equals(choice.getSelectedItem())));

        if (!dialog.showDialog()) {
            return dialog.wasBackPressed() ? ModalityScreenResult.back() : ModalityScreenResult.canceled();
        }

        ScopeModality modality = "Confocal".equals(choice.getSelectedItem())
                ? ScopeModality.CONFOCAL
                : ("Spinning Disk".equals(choice.getSelectedItem())
                ? ScopeModality.SPINNING_DISK
                : ScopeModality.WIDEFIELD);
        Double pinhole = modality == ScopeModality.CONFOCAL
                ? Double.valueOf(parsePositiveDouble(pinholeField.getText(), 1.0))
                : null;
        return ModalityScreenResult.of(modality, pinhole);
    }

    private ScreenResult<String> showMountingMediumScreen(SampleDepth sampleDepth, String currentHint) {
        PipelineDialog dialog = new PipelineDialog(DIALOG_TITLE + " - Mounting Medium");
        dialog.enableBackButton();
        dialog.addHeader("What are you mounting in?");
        String defaultChoice = defaultMountingChoice(sampleDepth, currentHint);
        JComboBox<String> choice = dialog.addChoice(
                "Medium",
                new String[]{
                        "Vectashield",
                        "ProLong Gold",
                        "CFM-3",
                        "Glycerol",
                        "Aqueous",
                        "Clearing-tissue medium",
                        "Unknown (skip)"
                },
                defaultChoice
        );
        if (!dialog.showDialog()) {
            return dialog.wasBackPressed() ? ScreenResult.back() : ScreenResult.canceled();
        }

        String selected = String.valueOf(choice.getSelectedItem());
        if ("Vectashield".equals(selected)) return ScreenResult.of("vectashield");
        if ("ProLong Gold".equals(selected)) return ScreenResult.of("prolong");
        if ("CFM-3".equals(selected)) return ScreenResult.of("cfm3");
        if ("Glycerol".equals(selected)) return ScreenResult.of("glycerol");
        if ("Aqueous".equals(selected)) return ScreenResult.of("aqueous");
        if ("Clearing-tissue medium".equals(selected)) return ScreenResult.of("clarity");
        return ScreenResult.of(null);
    }

    private static String defaultMountingChoice(SampleDepth sampleDepth, String currentHint) {
        String hint = resolvedMountingMediumHint(sampleDepth, currentHint);
        if (hint == null) {
            return "Unknown (skip)";
        }
        String normalized = hint.toLowerCase(Locale.ROOT);
        if (normalized.contains("vectashield")) return "Vectashield";
        if (normalized.contains("prolong")) return "ProLong Gold";
        if (normalized.contains("cfm3") || normalized.contains("cfm-3")) return "CFM-3";
        if (normalized.contains("glycer")) return "Glycerol";
        if (normalized.contains("aqueous") || normalized.contains("pbs") || normalized.contains("water")) {
            return "Aqueous";
        }
        if (normalized.contains("clarity")
                || normalized.contains("idisco")
                || normalized.contains("cubic")
                || normalized.contains("ce3d")) {
            return "Clearing-tissue medium";
        }
        return "Unknown (skip)";
    }

    private static String resolvedMountingMediumHint(SampleDepth sampleDepth, String hint) {
        if (sampleDepth == SampleDepth.THIN) {
            return null;
        }
        String trimmed = emptyToNull(hint);
        if (trimmed != null) {
            return trimmed;
        }
        if (sampleDepth == SampleDepth.CLEARED) {
            return "clarity";
        }
        return null;
    }

    private static double positiveOrDefault(Double value, double fallback) {
        if (value == null || Double.isNaN(value.doubleValue())
                || Double.isInfinite(value.doubleValue()) || value.doubleValue() <= 0.0) {
            return fallback;
        }
        return value.doubleValue();
    }

    private static double parsePositiveDouble(String raw, double fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed > 0.0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum Priority {
        SPEED,
        ACCURACY
    }

    public enum SampleDepth {
        THIN,
        DEEP,
        CLEARED
    }

    public static final class Answers {
        private final Priority priority;
        private final SampleDepth sampleDepth;
        private final ScopeModality scopeModality;
        private final Double pinholeAU;
        private final String mountingMediumHint;

        public Answers(Priority priority,
                       SampleDepth sampleDepth,
                       ScopeModality scopeModality,
                       Double pinholeAU,
                       String mountingMediumHint) {
            if (priority == null) {
                throw new IllegalArgumentException("priority is required.");
            }
            if (sampleDepth == null) {
                throw new IllegalArgumentException("sampleDepth is required.");
            }
            this.priority = priority;
            this.sampleDepth = sampleDepth;
            this.scopeModality = scopeModality;
            this.pinholeAU = pinholeAU;
            this.mountingMediumHint = emptyToNull(mountingMediumHint);
        }

        public Priority getPriority() {
            return priority;
        }

        public SampleDepth getSampleDepth() {
            return sampleDepth;
        }

        public ScopeModality getScopeModality() {
            return scopeModality;
        }

        public Double getPinholeAU() {
            return pinholeAU;
        }

        public String getMountingMediumHint() {
            return mountingMediumHint;
        }
    }

    public static final class Availability {
        private final boolean clij2Available;
        private final boolean dl2Available;
        private final boolean iterativeAvailable;

        public Availability(boolean clij2Available, boolean dl2Available, boolean iterativeAvailable) {
            this.clij2Available = clij2Available;
            this.dl2Available = dl2Available;
            this.iterativeAvailable = iterativeAvailable;
        }

        public static Availability fromCurrentRuntime() {
            return new Availability(
                    flash.pipeline.deconv.DeconvolutionAvailability.isClij2Available(),
                    flash.pipeline.deconv.DeconvolutionAvailability.isDL2Available(),
                    flash.pipeline.deconv.DeconvolutionAvailability.isIterativeDeconvolve3DAvailable()
            );
        }

        public boolean isClij2Available() {
            return clij2Available;
        }

        public boolean isDl2Available() {
            return dl2Available;
        }

        public boolean isIterativeAvailable() {
            return iterativeAvailable;
        }
    }

    public static final class Recommendation {
        private final String engineKey;
        private final Algorithm algorithm;
        private final PsfModel psfModel;
        private final ScopeModality scopeModality;
        private final Double pinholeAU;
        private final Double sampleRI;
        private final String mountingMediumHint;
        private final int iterations;
        private final double regularization;

        public Recommendation(String engineKey,
                              Algorithm algorithm,
                              PsfModel psfModel,
                              ScopeModality scopeModality,
                              Double pinholeAU,
                              Double sampleRI,
                              String mountingMediumHint,
                              int iterations,
                              double regularization) {
            this.engineKey = engineKey;
            this.algorithm = algorithm;
            this.psfModel = psfModel;
            this.scopeModality = scopeModality;
            this.pinholeAU = pinholeAU;
            this.sampleRI = sampleRI;
            this.mountingMediumHint = emptyToNull(mountingMediumHint);
            this.iterations = iterations;
            this.regularization = regularization;
        }

        public String getEngineKey() {
            return engineKey;
        }

        public Algorithm getAlgorithm() {
            return algorithm;
        }

        public PsfModel getPsfModel() {
            return psfModel;
        }

        public ScopeModality getScopeModality() {
            return scopeModality;
        }

        public Double getPinholeAU() {
            return pinholeAU;
        }

        public Double getSampleRI() {
            return sampleRI;
        }

        public String getMountingMediumHint() {
            return mountingMediumHint;
        }

        public int getIterations() {
            return iterations;
        }

        public double getRegularization() {
            return regularization;
        }
    }

    static final class IterationRecommendation {
        final int iterations;
        final double regularization;

        IterationRecommendation(int iterations, double regularization) {
            this.iterations = iterations;
            this.regularization = regularization;
        }
    }

    private static class ScreenResult<T> {
        final T value;
        final boolean back;
        final boolean canceled;

        ScreenResult(T value, boolean back, boolean canceled) {
            this.value = value;
            this.back = back;
            this.canceled = canceled;
        }

        static <T> ScreenResult<T> of(T value) {
            return new ScreenResult<T>(value, false, false);
        }

        static <T> ScreenResult<T> back() {
            return new ScreenResult<T>(null, true, false);
        }

        static <T> ScreenResult<T> canceled() {
            return new ScreenResult<T>(null, false, true);
        }
    }

    private static final class ModalityScreenResult extends ScreenResult<ScopeModality> {
        final ScopeModality modality;
        final Double pinholeAU;

        ModalityScreenResult(ScopeModality modality, Double pinholeAU, boolean back, boolean canceled) {
            super(modality, back, canceled);
            this.modality = modality;
            this.pinholeAU = pinholeAU;
        }

        static ModalityScreenResult of(ScopeModality modality, Double pinholeAU) {
            return new ModalityScreenResult(modality, pinholeAU, false, false);
        }

        static ModalityScreenResult back() {
            return new ModalityScreenResult(null, null, true, false);
        }

        static ModalityScreenResult canceled() {
            return new ModalityScreenResult(null, null, false, true);
        }
    }
}
