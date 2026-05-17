package flash.pipeline.ui.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.training.ImagePlusProvider;
import flash.pipeline.click.training.ObjectClassifierPersistence;
import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.click.training.ObjectFeatureExtractor;
import flash.pipeline.click.training.cellpose.CellposeDatasetPackager;
import flash.pipeline.click.training.cellpose.CellposeLocalTrainingService;
import flash.pipeline.click.training.stardist.StarDistDatasetPackager;
import flash.pipeline.click.training.stardist.StarDistLocalTrainingService;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.StarDistLinkingParams;
import flash.pipeline.segmentation.StarDistPostFilters;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.config.SegmentationMethodLauncherModel;
import ij.ImagePlus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Controller for the Train Custom Engine wizard. Swing renders this state; all
 * decisions that affect saved model/catalog/channel state live here.
 */
public final class TrainCustomEngineWorkflow {
    public static final int MIN_POSITIVE_CLICKS = 20;
    public static final int MIN_NEGATIVE_CLICKS = 20;

    public enum Base {
        CLASSICAL("Classical + RF post-filter (in-process training)", "RF"),
        ENHANCED_CLASSICAL("Enhanced Classical + RF post-filter (in-process training)", "RF"),
        STARDIST_RF("StarDist + RF post-filter (in-process training)", "StarDistRF"),
        CELLPOSE_RF("Cellpose + RF post-filter (in-process training)", "CellposeRF"),
        STARDIST("StarDist custom model (external training)", "StarDist"),
        CELLPOSE("Cellpose custom model (external training)", "Cellpose");

        public final String label;
        public final String shortLabel;

        Base(String label, String shortLabel) {
            this.label = label;
            this.shortLabel = shortLabel;
        }

        public boolean trainsRf() {
            return this == CLASSICAL || this == ENHANCED_CLASSICAL
                    || this == STARDIST_RF || this == CELLPOSE_RF;
        }

        public Base tokenBase() {
            if (this == STARDIST_RF) return STARDIST;
            if (this == CELLPOSE_RF) return CELLPOSE;
            return this;
        }
    }

    public enum Step {
        PICK_BASE,
        REVIEW_CLICKS,
        TRAIN,
        RESULT_REVIEW,
        APPLY,
        DONE,
        CANCELLED
    }

    public interface ChannelMethodStore {
        String getMethodToken();
        void setMethodToken(String token);
    }

    public interface ProgressListener {
        void update(double fraction, String message);
    }

    public interface RfTrainingService {
        ObjectClassifierTrainer.TrainingResult train(Base base,
                                                     ClickSelection selection,
                                                     ProgressListener progress) throws Exception;
    }

    public interface StarDistPackagingService {
        StarDistDatasetPackager.PackagingResult packageDataset(ClickSelection selection,
                                                               String sessionName,
                                                               ProgressListener progress) throws Exception;
    }

    public interface StarDistTrainingService {
        boolean isEnabled();

        StarDistLocalTrainingService.TrainingResult train(
                StarDistDatasetPackager.PackagingResult packageResult,
                String modelName,
                ProgressListener progress) throws Exception;
    }

    public interface CellposePackagingService {
        CellposeDatasetPackager.PackagingResult packageDataset(ClickSelection selection,
                                                               String sessionName,
                                                               String baseModel,
                                                               ProgressListener progress) throws Exception;
    }

    public interface CellposeTrainingService {
        boolean isEnabled();

        CellposeLocalTrainingService.TrainingResult train(
                CellposeDatasetPackager.PackagingResult packageResult,
                String modelName,
                ProgressListener progress) throws Exception;
    }

    public interface ModelCatalogService {
        ModelEntry saveRf(Path projectRoot,
                          String modelKey,
                          String name,
                          String description,
                          String baseToken,
                          ObjectClassifierTrainer.TrainingResult result) throws IOException;

        ModelEntry saveStarDist(Path projectRoot,
                                String modelKey,
                                String name,
                                String description,
                                Path modelFile,
                                String baseToken,
                                ClickSummary clickSummary,
                                StarDistDatasetPackager.PackagingResult packageResult) throws IOException;

        ModelEntry saveCellpose(Path projectRoot,
                                String modelKey,
                                String name,
                                String description,
                                Path modelFile,
                                String baseToken,
                                ClickSummary clickSummary,
                                CellposeDatasetPackager.PackagingResult packageResult) throws IOException;
    }

    public interface ModelKeyGenerator {
        String newModelKey(ModelCatalog catalog, ModelEntry.Engine engine, String displayName);
    }

    public static final ProgressListener NO_PROGRESS = new ProgressListener() {
        @Override public void update(double fraction, String message) {
        }
    };

    public static final class Services {
        public final RfTrainingService rfTrainingService;
        public final StarDistPackagingService starDistPackagingService;
        public final StarDistTrainingService starDistTrainingService;
        public final CellposePackagingService cellposePackagingService;
        public final CellposeTrainingService cellposeTrainingService;
        public final ModelCatalogService catalogService;
        public final ModelKeyGenerator modelKeyGenerator;
        public final Clock clock;

        public Services(RfTrainingService rfTrainingService,
                        StarDistPackagingService starDistPackagingService,
                        CellposePackagingService cellposePackagingService,
                        ModelCatalogService catalogService,
                        ModelKeyGenerator modelKeyGenerator,
                        Clock clock) {
            this(rfTrainingService, starDistPackagingService, null,
                    cellposePackagingService, null, catalogService, modelKeyGenerator, clock);
        }

        public Services(RfTrainingService rfTrainingService,
                        StarDistPackagingService starDistPackagingService,
                        StarDistTrainingService starDistTrainingService,
                        CellposePackagingService cellposePackagingService,
                        ModelCatalogService catalogService,
                        ModelKeyGenerator modelKeyGenerator,
                        Clock clock) {
            this(rfTrainingService, starDistPackagingService, starDistTrainingService,
                    cellposePackagingService, null, catalogService, modelKeyGenerator, clock);
        }

        public Services(RfTrainingService rfTrainingService,
                        StarDistPackagingService starDistPackagingService,
                        StarDistTrainingService starDistTrainingService,
                        CellposePackagingService cellposePackagingService,
                        CellposeTrainingService cellposeTrainingService,
                        ModelCatalogService catalogService,
                        ModelKeyGenerator modelKeyGenerator,
                        Clock clock) {
            this.rfTrainingService = rfTrainingService;
            this.starDistPackagingService = starDistPackagingService;
            this.starDistTrainingService = starDistTrainingService;
            this.cellposePackagingService = cellposePackagingService;
            this.cellposeTrainingService = cellposeTrainingService;
            this.catalogService = catalogService == null
                    ? new DefaultModelCatalogService()
                    : catalogService;
            this.modelKeyGenerator = modelKeyGenerator == null
                    ? new DefaultModelKeyGenerator()
                    : modelKeyGenerator;
            this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        }
    }

    public static final class ImageTrainingServices {
        private ImageTrainingServices() {
        }

        public static RfTrainingService rf(final ImagePlusProvider rawProvider,
                                           final ImagePlusProvider labelProvider,
                                           final int seed) {
            return new ImageRfTrainingService(rawProvider, labelProvider, seed);
        }

        public static StarDistPackagingService starDist(final Path projectRoot,
                                                        final int channelOneBased,
                                                        final ClickStore clickStore,
                                                        final ImagePlusProvider rawProvider,
                                                        final ImagePlusProvider labelProvider) {
            return new StarDistPackagingService() {
                @Override public StarDistDatasetPackager.PackagingResult packageDataset(
                        ClickSelection selection,
                        String sessionName,
                        ProgressListener progress) throws Exception {
                    ProgressListener safe = safeProgress(progress);
                    safe.update(0.05, "Packaging StarDist training dataset...");
                    StarDistDatasetPackager.PackagingResult result =
                            new StarDistDatasetPackager().packageDataset(
                                    projectRoot,
                                    sessionName,
                                    channelOneBased,
                                    selection == null ? clickStore : selection.toClickStore(),
                                    rawProvider,
                                    labelProvider,
                                    0);
                    safe.update(1.0, "StarDist dataset packaged.");
                    return result;
                }
            };
        }

        public static StarDistTrainingService starDistLocalIfEnabled() {
            final StarDistLocalTrainingService service = new StarDistLocalTrainingService();
            if (!service.isEnabled()) {
                return null;
            }
            return new StarDistTrainingService() {
                @Override public boolean isEnabled() {
                    return service.isEnabled();
                }

                @Override public StarDistLocalTrainingService.TrainingResult train(
                        StarDistDatasetPackager.PackagingResult packageResult,
                        String modelName,
                        final ProgressListener progress) throws Exception {
                    return service.train(packageResult, modelName,
                            new StarDistLocalTrainingService.ProgressSink() {
                                @Override public void update(double fraction, String message) {
                                    safeProgress(progress).update(fraction, message);
                                }
                            });
                }
            };
        }

        public static CellposePackagingService cellpose(final Path projectRoot,
                                                       final int channelOneBased,
                                                       final ClickStore clickStore,
                                                        final ImagePlusProvider rawProvider,
                                                        final ImagePlusProvider labelProvider) {
            return new CellposePackagingService() {
                @Override public CellposeDatasetPackager.PackagingResult packageDataset(
                        ClickSelection selection,
                        String sessionName,
                        String baseModel,
                        ProgressListener progress) throws Exception {
                    ProgressListener safe = safeProgress(progress);
                    safe.update(0.05, "Packaging Cellpose training dataset...");
                    CellposeDatasetPackager.PackagingResult result =
                            new CellposeDatasetPackager().packageDataset(
                                    projectRoot,
                                    sessionName,
                                    channelOneBased,
                                    selection == null ? clickStore : selection.toClickStore(),
                                    rawProvider,
                                    labelProvider,
                                    baseModel);
                    safe.update(1.0, "Cellpose dataset packaged.");
                    return result;
                }
            };
        }

        public static CellposeTrainingService cellposeLocalIfEnabled() {
            if (!SegmentationMethodLauncherModel.isTrainCustomEngineUiEnabled()) {
                return null;
            }
            final CellposeLocalTrainingService service = new CellposeLocalTrainingService();
            if (!service.isEnabled()) {
                return null;
            }
            return new CellposeTrainingService() {
                @Override public boolean isEnabled() {
                    return service.isEnabled();
                }

                @Override public CellposeLocalTrainingService.TrainingResult train(
                        CellposeDatasetPackager.PackagingResult packageResult,
                        String modelName,
                        final ProgressListener progress) throws Exception {
                    return service.train(packageResult, modelName,
                            new CellposeLocalTrainingService.ProgressSink() {
                                @Override public void update(double fraction, String message) {
                                    safeProgress(progress).update(fraction, message);
                                }
                            });
                }
            };
        }
    }

    public static final class ClickSummary {
        public final int positive;
        public final int negative;
        public final int imageCount;
        public final List<ImageSummary> perImage;

        ClickSummary(int positive, int negative, List<ImageSummary> perImage) {
            this.positive = positive;
            this.negative = negative;
            this.perImage = Collections.unmodifiableList(new ArrayList<ImageSummary>(
                    perImage == null ? Collections.<ImageSummary>emptyList() : perImage));
            int included = 0;
            for (ImageSummary image : this.perImage) {
                if (image != null && !image.excluded && image.total() > 0) {
                    included++;
                }
            }
            this.imageCount = included;
        }
    }

    public static final class ImageSummary {
        public final String imageName;
        public final int positive;
        public final int negative;
        public final boolean excluded;

        ImageSummary(String imageName, int positive, int negative, boolean excluded) {
            this.imageName = imageName == null ? "" : imageName;
            this.positive = positive;
            this.negative = negative;
            this.excluded = excluded;
        }

        public int total() {
            return positive + negative;
        }
    }

    public static final class ClickSelection {
        public final int channelOneBased;
        public final List<ClickStore.Click> clicks;
        public final ClickSummary summary;

        ClickSelection(int channelOneBased, List<ClickStore.Click> clicks, ClickSummary summary) {
            this.channelOneBased = channelOneBased;
            this.clicks = Collections.unmodifiableList(new ArrayList<ClickStore.Click>(
                    clicks == null ? Collections.<ClickStore.Click>emptyList() : clicks));
            this.summary = summary == null
                    ? new ClickSummary(0, 0, Collections.<ImageSummary>emptyList())
                    : summary;
        }

        public ClickStore toClickStore() {
            ClickStore out = new ClickStore();
            for (int i = 0; i < clicks.size(); i++) {
                out.add(clicks.get(i));
            }
            return out;
        }

        public Set<Integer> labelsForImage(String imageName, ClickStore.Verdict verdict) {
            Set<Integer> out = new LinkedHashSet<Integer>();
            for (int i = 0; i < clicks.size(); i++) {
                ClickStore.Click click = clicks.get(i);
                if (click == null || click.verdict != verdict) continue;
                if (safe(click.imageName).equals(safe(imageName)) && click.label > 0) {
                    out.add(Integer.valueOf(click.label));
                }
            }
            return out;
        }

        public List<String> imageNames() {
            List<String> names = new ArrayList<String>();
            Set<String> seen = new LinkedHashSet<String>();
            for (int i = 0; i < clicks.size(); i++) {
                String name = safe(clicks.get(i) == null ? "" : clicks.get(i).imageName);
                if (!seen.contains(name)) {
                    names.add(name);
                    seen.add(name);
                }
            }
            return names;
        }
    }

    public static final class TrainStepResult {
        public final Base base;
        public final ObjectClassifierTrainer.TrainingResult rfResult;
        public final StarDistDatasetPackager.PackagingResult starDistPackage;
        public final StarDistLocalTrainingService.TrainingResult starDistTraining;
        public final CellposeDatasetPackager.PackagingResult cellposePackage;
        public final CellposeLocalTrainingService.TrainingResult cellposeTraining;
        public final String message;

        TrainStepResult(Base base,
                        ObjectClassifierTrainer.TrainingResult rfResult,
                        StarDistDatasetPackager.PackagingResult starDistPackage,
                        StarDistLocalTrainingService.TrainingResult starDistTraining,
                        CellposeDatasetPackager.PackagingResult cellposePackage,
                        CellposeLocalTrainingService.TrainingResult cellposeTraining,
                        String message) {
            this.base = base;
            this.rfResult = rfResult;
            this.starDistPackage = starDistPackage;
            this.starDistTraining = starDistTraining;
            this.cellposePackage = cellposePackage;
            this.cellposeTraining = cellposeTraining;
            this.message = message == null ? "" : message;
        }
    }

    private final Path projectRoot;
    private final int channelOneBased;
    private final String channelName;
    private final ClickStore clickStore;
    private final ChannelMethodStore methodStore;
    private final Services services;
    private final String previousMethodToken;
    private final Map<Base, String> baseTokens = new LinkedHashMap<Base, String>();
    private final Set<String> excludedImages = new LinkedHashSet<String>();

    private Step step = Step.PICK_BASE;
    private Base selectedBase = Base.CLASSICAL;
    private ObjectClassifierTrainer.TrainingResult rfResult;
    private StarDistDatasetPackager.PackagingResult starDistPackage;
    private StarDistLocalTrainingService.TrainingResult starDistTraining;
    private CellposeDatasetPackager.PackagingResult cellposePackage;
    private CellposeLocalTrainingService.TrainingResult cellposeTraining;
    private Path externalModelFile;
    private ModelEntry savedEntry;
    private String recommendedMethodToken;
    private String warningMessage = "";

    public TrainCustomEngineWorkflow(Path projectRoot,
                                     int channelOneBased,
                                     String channelName,
                                     ClickStore clickStore,
                                     ChannelMethodStore methodStore,
                                     Services services) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null");
        }
        if (methodStore == null) {
            throw new IllegalArgumentException("methodStore must not be null");
        }
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.channelOneBased = Math.max(1, channelOneBased);
        this.channelName = clean(channelName, "C" + this.channelOneBased);
        this.clickStore = clickStore == null ? new ClickStore() : clickStore;
        this.methodStore = methodStore;
        this.services = services == null
                ? new Services(null, null, null, null, null, null)
                : services;
        this.previousMethodToken = clean(methodStore.getMethodToken(), "classical");
        this.baseTokens.put(Base.CLASSICAL, "classical");
        this.baseTokens.put(Base.ENHANCED_CLASSICAL,
                "enhanced_classical:thresh=0:minSize=100:maxSize=2147483647");
        SegmentationMethod previous = SegmentationTokenParser.parseLenient(this.previousMethodToken);
        this.baseTokens.put(Base.STARDIST, previous.isStarDist()
                ? this.previousMethodToken
                : "stardist:" + BinConfig.DEFAULT_STARDIST_PROB_THRESH + ":"
                + BinConfig.DEFAULT_STARDIST_NMS_THRESH);
        this.baseTokens.put(Base.CELLPOSE, previous.isCellpose()
                ? this.previousMethodToken
                : "cellpose:" + BinConfig.DEFAULT_CELLPOSE_DIAMETER + ":"
                + BinConfig.DEFAULT_CELLPOSE_FLOW_THRESHOLD + ":"
                + BinConfig.DEFAULT_CELLPOSE_CELLPROB_THRESHOLD
                + ":gpu=" + BinConfig.DEFAULT_CELLPOSE_USE_GPU
                + ":chan2=-1:model=" + SegmentationMethod.DEFAULT_CELLPOSE_MODEL_KEY);
    }

    public Step step() {
        return step;
    }

    public Base selectedBase() {
        return selectedBase;
    }

    public String previousMethodToken() {
        return previousMethodToken;
    }

    public String recommendedMethodToken() {
        return recommendedMethodToken;
    }

    public ModelEntry savedEntry() {
        return savedEntry;
    }

    public ObjectClassifierTrainer.TrainingResult rfResult() {
        return rfResult;
    }

    public StarDistDatasetPackager.PackagingResult starDistPackage() {
        return starDistPackage;
    }

    public StarDistLocalTrainingService.TrainingResult starDistTraining() {
        return starDistTraining;
    }

    public CellposeDatasetPackager.PackagingResult cellposePackage() {
        return cellposePackage;
    }

    public CellposeLocalTrainingService.TrainingResult cellposeTraining() {
        return cellposeTraining;
    }

    public Path externalModelFile() {
        return externalModelFile;
    }

    public String warningMessage() {
        return warningMessage;
    }

    public void setBaseToken(Base base, String token) {
        if (base == null) return;
        String cleaned = clean(token, null);
        if (cleaned != null) {
            baseTokens.put(base.tokenBase(), cleaned);
        }
    }

    public String baseToken(Base base) {
        Base key = base == null ? Base.CLASSICAL : base.tokenBase();
        String token = baseTokens.get(key);
        return clean(token, key == Base.CLASSICAL ? "classical" : previousMethodToken);
    }

    public void selectBase(Base base) {
        selectedBase = base == null ? Base.CLASSICAL : base;
        rfResult = null;
        starDistPackage = null;
        starDistTraining = null;
        cellposePackage = null;
        cellposeTraining = null;
        externalModelFile = null;
        savedEntry = null;
        recommendedMethodToken = null;
        warningMessage = "";
        step = Step.REVIEW_CLICKS;
    }

    public void routeToClickPreview() {
        methodStore.setMethodToken(baseToken(selectedBase));
        step = Step.REVIEW_CLICKS;
    }

    public ClickSummary clickSummary() {
        return clickSelection().summary;
    }

    public ClickSelection clickSelection() {
        List<ClickStore.Click> source = clickStore.forChannel(channelOneBased);
        List<ClickStore.Click> included = new ArrayList<ClickStore.Click>();
        LinkedHashMap<String, Counts> allCounts = new LinkedHashMap<String, Counts>();
        for (int i = 0; i < source.size(); i++) {
            ClickStore.Click click = source.get(i);
            if (click == null || click.channelOneBased != channelOneBased) continue;
            String image = safe(click.imageName);
            Counts counts = allCounts.get(image);
            if (counts == null) {
                counts = new Counts();
                allCounts.put(image, counts);
            }
            if (click.verdict == ClickStore.Verdict.POSITIVE) counts.positive++;
            else counts.negative++;
            if (!excludedImages.contains(image)) {
                included.add(click);
            }
        }

        List<ImageSummary> perImage = new ArrayList<ImageSummary>();
        int positive = 0;
        int negative = 0;
        List<String> imageNames = new ArrayList<String>(allCounts.keySet());
        Collections.sort(imageNames);
        for (int i = 0; i < imageNames.size(); i++) {
            String image = imageNames.get(i);
            Counts counts = allCounts.get(image);
            boolean excluded = excludedImages.contains(image);
            if (!excluded) {
                positive += counts.positive;
                negative += counts.negative;
            }
            perImage.add(new ImageSummary(image, counts.positive, counts.negative, excluded));
        }
        return new ClickSelection(channelOneBased, included,
                new ClickSummary(positive, negative, perImage));
    }

    public void setImageExcluded(String imageName, boolean excluded) {
        String key = safe(imageName);
        if (excluded) {
            excludedImages.add(key);
        } else {
            excludedImages.remove(key);
        }
    }

    public boolean canProceedFromClicks() {
        ClickSummary summary = clickSummary();
        return summary.positive >= MIN_POSITIVE_CLICKS
                && summary.negative >= MIN_NEGATIVE_CLICKS;
    }

    public String clickGateMessage() {
        ClickSummary summary = clickSummary();
        if (canProceedFromClicks()) {
            return "Ready with " + summary.positive + " positive and "
                    + summary.negative + " negative clicks.";
        }
        return "Need at least " + MIN_POSITIVE_CLICKS + " positive and "
                + MIN_NEGATIVE_CLICKS + " negative clicks. Current counts: "
                + summary.positive + " positive, " + summary.negative + " negative.";
    }

    public TrainStepResult runTrainingStep(ProgressListener progress) throws Exception {
        if (!canProceedFromClicks()) {
            throw new IllegalStateException(clickGateMessage());
        }
        ProgressListener safe = safeProgress(progress);
        ClickSelection selection = clickSelection();
        step = Step.TRAIN;
        warningMessage = "";
        if (selectedBase == Base.STARDIST) {
            starDistTraining = null;
            externalModelFile = null;
        } else if (selectedBase == Base.CELLPOSE) {
            cellposeTraining = null;
            externalModelFile = null;
        }
        if (selectedBase.trainsRf()) {
            if (services.rfTrainingService == null) {
                throw new IllegalStateException("Random Forest training is not available in this context.");
            }
            safe.update(0.05, "Training Random Forest...");
            rfResult = services.rfTrainingService.train(selectedBase, selection, safe);
            safe.update(1.0, "Random Forest training complete.");
            if (rfResult != null
                    && rfResult.quality == ObjectClassifierTrainer.QualityFlag.LOW) {
                warningMessage = "Training quality flagged LOW (cross-val accuracy "
                        + formatAccuracy(rfResult.crossValAccuracy)
                        + "). Consider adding more clicks before saving.";
            }
            step = Step.RESULT_REVIEW;
            return new TrainStepResult(selectedBase, rfResult, null, null, null, null,
                    warningMessage.isEmpty() ? "Training complete." : warningMessage);
        }
        String sessionName = datasetSessionName();
        if (selectedBase == Base.STARDIST) {
            if (services.starDistPackagingService == null) {
                throw new IllegalStateException("StarDist dataset packaging is not available in this context.");
            }
            starDistPackage = services.starDistPackagingService.packageDataset(
                    selection, sessionName, safe);
            step = Step.RESULT_REVIEW;
            if (services.starDistTrainingService != null
                    && services.starDistTrainingService.isEnabled()) {
                try {
                    safe.update(0.05, "Starting local StarDist training...");
                    starDistTraining = services.starDistTrainingService.train(
                            starDistPackage, defaultModelName(), safe);
                    externalModelFile = starDistTraining.outputZip;
                    safe.update(1.0, "Local StarDist training complete.");
                    return new TrainStepResult(selectedBase, null, starDistPackage,
                            starDistTraining, null, null,
                            "Local StarDist training complete.");
                } catch (Exception e) {
                    warningMessage = "Local StarDist training failed; dataset package remains available "
                            + "for ZeroCostDL4Mic fallback. " + clean(e.getMessage(), "");
                }
            }
            return new TrainStepResult(selectedBase, null, starDistPackage, null, null, null,
                    warningMessage.isEmpty()
                            ? "StarDist dataset packaged."
                            : warningMessage);
        }
        if (services.cellposePackagingService == null) {
            throw new IllegalStateException("Cellpose dataset packaging is not available in this context.");
        }
        cellposePackage = services.cellposePackagingService.packageDataset(
                selection, sessionName, cellposeBaseModel(), safe);
        step = Step.RESULT_REVIEW;
        if (cellposePackage != null && cellposePackage.trainingWarning != null
                && !cellposePackage.trainingWarning.isEmpty()) {
            warningMessage = cellposePackage.trainingWarning;
        }
        if (services.cellposeTrainingService != null
                && services.cellposeTrainingService.isEnabled()) {
            try {
                safe.update(0.05, "Starting local Cellpose training...");
                cellposeTraining = services.cellposeTrainingService.train(
                        cellposePackage, defaultModelName(), safe);
                externalModelFile = cellposeTraining.modelFile;
                safe.update(1.0, "Local Cellpose training complete.");
                return new TrainStepResult(selectedBase, null, null, null,
                        cellposePackage, cellposeTraining,
                        warningMessage.isEmpty()
                                ? "Local Cellpose training complete."
                                : "Local Cellpose training complete. " + warningMessage);
            } catch (Exception e) {
                warningMessage = appendWarning(warningMessage,
                        "Local Cellpose training failed; dataset package remains available "
                                + "for the manual command fallback. " + clean(e.getMessage(), ""));
            }
        }
        return new TrainStepResult(selectedBase, null, null, null, cellposePackage, null,
                warningMessage.isEmpty()
                        ? "Cellpose dataset packaged."
                        : warningMessage);
    }

    public boolean acceptExternalModelFile(Path modelFile) throws IOException {
        Path file = modelFile == null ? null : modelFile.toAbsolutePath().normalize();
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("Model file does not exist: " + file);
        }
        if (selectedBase == Base.STARDIST) {
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                throw new IOException("StarDist model import expects a .zip file.");
            }
        }
        externalModelFile = file;
        step = Step.RESULT_REVIEW;
        return true;
    }

    public String defaultModelName() {
        String date = LocalDate.now(services.clock).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return channelName + " " + selectedBase.shortLabel + " " + date;
    }

    public String defaultDescription() {
        ClickSummary summary = clickSummary();
        return summary.positive + " positive / " + summary.negative
                + " negative clicks across " + summary.imageCount + " images.";
    }

    public ModelEntry saveModel(String requestedName, String requestedDescription) throws Exception {
        String name = clean(requestedName, defaultModelName());
        String description = clean(requestedDescription, defaultDescription());
        ModelCatalog catalog = ModelCatalogIO.read(projectRoot);
        if (selectedBase.trainsRf()) {
            if (rfResult == null) {
                throw new IllegalStateException("Train the Random Forest before saving it.");
            }
            String key = services.modelKeyGenerator.newModelKey(
                    catalog, ModelEntry.Engine.SMILE_RF, name);
            savedEntry = services.catalogService.saveRf(projectRoot, key, name,
                    description, baseToken(selectedBase), rfResult);
            recommendedMethodToken = trainedRfToken(savedEntry.modelKey, baseToken(selectedBase));
        } else if (selectedBase == Base.STARDIST) {
            if (externalModelFile == null) {
                throw new IllegalStateException("Choose the trained StarDist .zip before saving.");
            }
            String key = services.modelKeyGenerator.newModelKey(
                    catalog, ModelEntry.Engine.STARDIST, name);
            savedEntry = services.catalogService.saveStarDist(projectRoot, key, name,
                    description, externalModelFile, baseToken(Base.STARDIST),
                    clickSummary(), starDistPackage);
            recommendedMethodToken = canonicalStarDistToken(savedEntry.modelKey);
        } else {
            if (externalModelFile == null) {
                throw new IllegalStateException("Choose the trained Cellpose model file before saving.");
            }
            String key = services.modelKeyGenerator.newModelKey(
                    catalog, ModelEntry.Engine.CELLPOSE, name);
            savedEntry = services.catalogService.saveCellpose(projectRoot, key, name,
                    description, externalModelFile, baseToken(Base.CELLPOSE),
                    clickSummary(), cellposePackage);
            recommendedMethodToken = canonicalCellposeToken(savedEntry.modelKey);
        }
        step = Step.APPLY;
        return savedEntry;
    }

    public void applyRecommended() {
        if (recommendedMethodToken == null || recommendedMethodToken.trim().isEmpty()) {
            throw new IllegalStateException("No saved model is ready to apply.");
        }
        methodStore.setMethodToken(recommendedMethodToken);
        step = Step.DONE;
    }

    public void keepCurrentMethod() {
        methodStore.setMethodToken(previousMethodToken);
        step = Step.DONE;
    }

    public void cancel() {
        methodStore.setMethodToken(previousMethodToken);
        step = Step.CANCELLED;
    }

    private String trainedRfToken(String modelKey, String baseToken) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("modelKey", clean(modelKey, ""));
        params.put("base", clean(baseToken, "classical"));
        return SegmentationTokenParser.format(new SegmentationMethod(
                SegmentationMethod.Engine.TRAINED_RF, params, ""));
    }

    private String canonicalStarDistToken(String modelKey) {
        SegmentationMethod method = SegmentationTokenParser.parseLenient(baseToken(Base.STARDIST));
        StarDistLinkingParams linking = SegmentationMethod.starDistLinking(method);
        StarDistPostFilters filters = SegmentationMethod.starDistPostFilters(method);
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("prob", valueOrDefault(method.params.get("prob"),
                String.valueOf(SegmentationMethod.starDistProb(method))));
        params.put("nms", valueOrDefault(method.params.get("nms"),
                String.valueOf(SegmentationMethod.starDistNms(method))));
        params.put("linking", valueOrDefault(method.params.get("linking"),
                String.valueOf(linking.linkingMaxDistance)));
        params.put("gapClosing", valueOrDefault(method.params.get("gapClosing"),
                String.valueOf(linking.gapClosingMaxDistance)));
        if (linking.maxFrameGap != BinConfig.DEFAULT_STARDIST_MAX_FRAME_GAP) {
            params.put("frameGap", valueOrDefault(method.params.get("frameGap"),
                    String.valueOf(linking.maxFrameGap)));
        }
        params.put("area", valueOrDefault(method.params.get("area"),
                String.valueOf(filters.areaMin) + "-"
                + (Double.isInfinite(filters.areaMax)
                ? "Infinity"
                : String.valueOf(filters.areaMax))));
        params.put("quality", valueOrDefault(method.params.get("quality"),
                String.valueOf(filters.qualityMin)));
        params.put("intensity", valueOrDefault(method.params.get("intensity"),
                String.valueOf(filters.intensityMin)));
        params.put("model", clean(modelKey, SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY));
        return SegmentationTokenParser.format(new SegmentationMethod(
                SegmentationMethod.Engine.STARDIST, params, ""));
    }

    private String canonicalCellposeToken(String modelKey) {
        SegmentationMethod method = SegmentationTokenParser.parseLenient(baseToken(Base.CELLPOSE));
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("diameter", String.valueOf(SegmentationMethod.cellposeDiameter(method)));
        params.put("flow", String.valueOf(SegmentationMethod.cellposeFlow(method)));
        params.put("cellprob", String.valueOf(SegmentationMethod.cellposeCellprob(method)));
        params.put("gpu", String.valueOf(SegmentationMethod.cellposeUseGpu(method)));
        params.put("chan2", String.valueOf(SegmentationMethod.cellposeChan2(method)));
        params.put("model", clean(modelKey, SegmentationMethod.DEFAULT_CELLPOSE_MODEL_KEY));
        return SegmentationTokenParser.format(new SegmentationMethod(
                SegmentationMethod.Engine.CELLPOSE, params, ""));
    }

    private String datasetSessionName() {
        String date = LocalDate.now(services.clock).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return date + "_" + channelName + "_" + selectedBase.shortLabel;
    }

    private String cellposeBaseModel() {
        SegmentationMethod method = SegmentationTokenParser.parseLenient(baseToken(Base.CELLPOSE));
        return SegmentationMethod.cellposeModelKey(method);
    }

    private static ProgressListener safeProgress(ProgressListener progress) {
        return progress == null ? NO_PROGRESS : progress;
    }

    private static String formatAccuracy(double accuracy) {
        if (!Double.isFinite(accuracy)) return "unknown";
        return String.format(Locale.ROOT, "%.2f", Double.valueOf(accuracy));
    }

    private static String clean(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }

    private static String appendWarning(String current, String addition) {
        String left = current == null ? "" : current.trim();
        String right = addition == null ? "" : addition.trim();
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + " " + right;
    }

    private static String valueOrDefault(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Counts {
        int positive;
        int negative;
    }

    private static final class ImageRfTrainingService implements RfTrainingService {
        private final ImagePlusProvider rawProvider;
        private final ImagePlusProvider labelProvider;
        private final int seed;

        ImageRfTrainingService(ImagePlusProvider rawProvider,
                               ImagePlusProvider labelProvider,
                               int seed) {
            this.rawProvider = rawProvider;
            this.labelProvider = labelProvider;
            this.seed = seed;
        }

        @Override public ObjectClassifierTrainer.TrainingResult train(
                Base base,
                ClickSelection selection,
                ProgressListener progress) throws Exception {
            if (rawProvider == null || labelProvider == null) {
                throw new IllegalStateException("Training image providers are not available.");
            }
            ProgressListener safe = safeProgress(progress);
            ObjectFeatureExtractor extractor = new ObjectFeatureExtractor();
            List<ObjectFeatureExtractor.FeatureRow> positives =
                    new ArrayList<ObjectFeatureExtractor.FeatureRow>();
            List<ObjectFeatureExtractor.FeatureRow> negatives =
                    new ArrayList<ObjectFeatureExtractor.FeatureRow>();

            List<String> imageNames = selection == null
                    ? Collections.<String>emptyList()
                    : selection.imageNames();
            int total = Math.max(1, imageNames.size());
            for (int i = 0; i < imageNames.size(); i++) {
                String imageName = imageNames.get(i);
                safe.update(i / (double) total,
                        "Extracting training features from " + imageName + "...");
                Set<Integer> positiveLabels =
                        selection.labelsForImage(imageName, ClickStore.Verdict.POSITIVE);
                Set<Integer> negativeLabels =
                        selection.labelsForImage(imageName, ClickStore.Verdict.NEGATIVE);
                Set<Integer> labels = new HashSet<Integer>();
                labels.addAll(positiveLabels);
                labels.addAll(negativeLabels);
                if (labels.isEmpty()) continue;

                ImagePlus raw = null;
                ImagePlus labelsImage = null;
                try {
                    raw = rawProvider.get(imageName);
                    labelsImage = labelProvider.get(imageName);
                    List<ObjectFeatureExtractor.FeatureRow> rows =
                            extractor.extractFromLabelImage(
                                    labelsImage, raw, cellprobImage(labelsImage), labels);
                    Map<Integer, ObjectFeatureExtractor.FeatureRow> byLabel =
                            rowsByLabel(rows);
                    addRows(byLabel, positiveLabels, positives);
                    addRows(byLabel, negativeLabels, negatives);
                } finally {
                    close(raw);
                    close(labelsImage);
                }
            }
            safe.update(0.85, "Training Random Forest on click examples...");
            return new ObjectClassifierTrainer().train(positives, negatives, seed);
        }

        private static Map<Integer, ObjectFeatureExtractor.FeatureRow> rowsByLabel(
                List<ObjectFeatureExtractor.FeatureRow> rows) {
            Map<Integer, ObjectFeatureExtractor.FeatureRow> out =
                    new HashMap<Integer, ObjectFeatureExtractor.FeatureRow>();
            if (rows == null) return out;
            for (int i = 0; i < rows.size(); i++) {
                ObjectFeatureExtractor.FeatureRow row = rows.get(i);
                if (row != null) out.put(Integer.valueOf(row.label), row);
            }
            return out;
        }

        private static void addRows(Map<Integer, ObjectFeatureExtractor.FeatureRow> byLabel,
                                    Set<Integer> labels,
                                    List<ObjectFeatureExtractor.FeatureRow> target) {
            for (Integer label : labels) {
                ObjectFeatureExtractor.FeatureRow row = byLabel.get(label);
                if (row != null) target.add(row);
            }
        }

        private static void close(ImagePlus image) {
            if (image == null) return;
            image.changes = false;
            image.close();
            image.flush();
        }

        private static ImagePlus cellprobImage(ImagePlus labelsImage) {
            if (labelsImage == null) return null;
            try {
                Object property = labelsImage.getProperty(Cellpose3DRunner.CELLPROB_IMAGE_PROPERTY);
                return property instanceof ImagePlus ? (ImagePlus) property : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public static final class DefaultModelCatalogService implements ModelCatalogService {
        @Override public ModelEntry saveRf(Path projectRoot,
                                           String modelKey,
                                           String name,
                                           String description,
                                           String baseToken,
                                           ObjectClassifierTrainer.TrainingResult result) throws IOException {
            Path tempDir = Files.createTempDirectory("flash-trained-rf-");
            Path tempModel = tempDir.resolve(ObjectClassifierPersistence.MODEL_FILENAME);
            try {
                ObjectClassifierPersistence.saveModel(tempModel, result.model);
                ModelCatalog catalog = ModelCatalogIO.read(projectRoot);
                ModelEntry entry = ObjectClassifierPersistence.catalogEntry(
                        modelKey, name, description, baseToken, result);
                ModelEntry saved = catalog.add(entry, tempModel);
                ModelCatalogIO.writeProject(projectRoot, catalog);
                return saved;
            } finally {
                deleteQuietly(tempModel);
                deleteQuietly(tempDir);
            }
        }

        @Override public ModelEntry saveStarDist(Path projectRoot,
                                                 String modelKey,
                                                 String name,
                                                 String description,
                                                 Path modelFile,
                                                 String baseToken,
                                                 ClickSummary clickSummary,
                                                 StarDistDatasetPackager.PackagingResult packageResult) throws IOException {
            ModelCatalog catalog = ModelCatalogIO.read(projectRoot);
            ModelEntry saved = catalog.add(new ModelEntry(
                    modelKey,
                    name,
                    description,
                    ModelEntry.Engine.STARDIST,
                    ModelEntry.Source.USER_TRAINED,
                    null,
                    null,
                    null,
                    null,
                    clean(baseToken, "stardist"),
                    starDistDefaults(),
                    trainingMetadata(clickSummary, packageResult == null ? null : packageResult.outputDir),
                    false), modelFile);
            ModelCatalogIO.writeProject(projectRoot, catalog);
            return saved;
        }

        @Override public ModelEntry saveCellpose(Path projectRoot,
                                                 String modelKey,
                                                 String name,
                                                 String description,
                                                 Path modelFile,
                                                 String baseToken,
                                                 ClickSummary clickSummary,
                                                 CellposeDatasetPackager.PackagingResult packageResult) throws IOException {
            ModelCatalog catalog = ModelCatalogIO.read(projectRoot);
            ModelEntry saved = catalog.add(new ModelEntry(
                    modelKey,
                    name,
                    description,
                    ModelEntry.Engine.CELLPOSE,
                    ModelEntry.Source.USER_TRAINED,
                    null,
                    null,
                    null,
                    null,
                    clean(baseToken, "cellpose"),
                    cellposeDefaults(),
                    trainingMetadata(clickSummary, packageResult == null ? null : packageResult.outputDir),
                    true), modelFile);
            ModelCatalogIO.writeProject(projectRoot, catalog);
            return saved;
        }

        private static Map<String, Object> starDistDefaults() {
            Map<String, Object> defaults = new LinkedHashMap<String, Object>();
            defaults.put("probThresh", Double.valueOf(BinConfig.DEFAULT_STARDIST_PROB_THRESH));
            defaults.put("nmsThresh", Double.valueOf(BinConfig.DEFAULT_STARDIST_NMS_THRESH));
            return defaults;
        }

        private static Map<String, Object> cellposeDefaults() {
            Map<String, Object> defaults = new LinkedHashMap<String, Object>();
            defaults.put("diameter", Double.valueOf(BinConfig.DEFAULT_CELLPOSE_DIAMETER));
            defaults.put("flowThreshold", Double.valueOf(BinConfig.DEFAULT_CELLPOSE_FLOW_THRESHOLD));
            defaults.put("cellprobThreshold", Double.valueOf(BinConfig.DEFAULT_CELLPOSE_CELLPROB_THRESHOLD));
            return defaults;
        }

        private static Map<String, Object> trainingMetadata(ClickSummary summary, Path datasetDir) {
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("qualityFlag", "USER_PROVIDED");
            if (summary != null) {
                metadata.put("positiveExamples", Integer.valueOf(summary.positive));
                metadata.put("negativeExamples", Integer.valueOf(summary.negative));
                metadata.put("imageCount", Integer.valueOf(summary.imageCount));
            }
            if (datasetDir != null) {
                metadata.put("trainingDataset", datasetDir.toString());
            }
            return metadata;
        }

        private static void deleteQuietly(Path path) {
            if (path == null) return;
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }

    public static final class DefaultModelKeyGenerator implements ModelKeyGenerator {
        @Override public String newModelKey(ModelCatalog catalog,
                                            ModelEntry.Engine engine,
                                            String displayName) {
            String prefix = engine == null ? "model" : engine.jsonValue();
            String slug = slug(displayName);
            if (slug.isEmpty()) slug = "model";
            String base = prefix + "_" + slug;
            String key = base;
            int suffix = 2;
            while (catalog != null && catalog.get(key).isPresent()) {
                key = base + "_" + suffix;
                suffix++;
            }
            return key;
        }

        private static String slug(String value) {
            String input = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            StringBuilder out = new StringBuilder();
            boolean separator = false;
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    out.append(c);
                    separator = false;
                } else if (!separator && out.length() > 0) {
                    out.append('_');
                    separator = true;
                }
            }
            while (out.length() > 0 && out.charAt(out.length() - 1) == '_') {
                out.deleteCharAt(out.length() - 1);
            }
            return out.toString();
        }
    }

    public static Comparator<ImageSummary> imageSummaryComparator() {
        return new Comparator<ImageSummary>() {
            @Override public int compare(ImageSummary left, ImageSummary right) {
                return safe(left == null ? "" : left.imageName)
                        .compareToIgnoreCase(safe(right == null ? "" : right.imageName));
            }
        };
    }
}
