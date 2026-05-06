package flash.pipeline.analyses.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.marker.MarkerLibrary;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.wizard.SegmentationEnginePicker;
import flash.pipeline.ui.wizard.MarkerTypeahead;
import flash.pipeline.ui.wizard.WizardFlow;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;

import ij.IJ;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Marker-aware setup helper for Create Bin File.
 */
public class ChannelSetupWizard extends WizardFlow {

    public static final String SIGNAL_DIM = "Dim";
    public static final String SIGNAL_TYPICAL = "Typical";
    public static final String SIGNAL_BRIGHT = "Bright";
    public static final String CROWDING_SPARSE = "Sparse";
    public static final String CROWDING_CROWDED = "Crowded";
    public static final String MARKER_CUSTOM = "other_custom";
    public static final String MARKER_AUTOFLUORESCENCE = "autofluorescence";

    private final MetadataDiagnostics.SeriesInfo seriesInfo;
    private final MarkerLibrary library;
    private final SegmentationEnginePicker.EngineAvailability availability;

    public ChannelSetupWizard(MainPanelBinding panel,
                              MetadataDiagnostics.SeriesInfo seriesInfo,
                              boolean headless) throws IOException {
        this(panel, seriesInfo, MarkerLibraryIO.loadBundled(),
                new SegmentationEnginePicker.EngineAvailability(
                        flash.pipeline.stardist.StarDistDetector.isAvailable(),
                        flash.pipeline.cellpose.CellposeRuntime.probeConfigured().ready),
                headless);
    }

    public ChannelSetupWizard(MainPanelBinding panel,
                              MetadataDiagnostics.SeriesInfo seriesInfo,
                              MarkerLibrary library,
                              SegmentationEnginePicker.EngineAvailability availability,
                              boolean headless) {
        super("Channel Setup Helper", panel, headless);
        this.seriesInfo = seriesInfo;
        this.library = library;
        this.availability = availability;
        register(new MarkerScreen());
        register(new SignalScreen());
        register(new CrowdingScreen());
        register(new ZSliceScreen());
    }

    public DerivedConfig deriveCurrentConfig() {
        return deriveConfig(seriesInfo, currentAnswers(), library, availability);
    }

    public static DerivedConfig deriveConfig(MetadataDiagnostics.SeriesInfo info,
                                             Map<String, Object> answers,
                                             MarkerLibrary library,
                                             SegmentationEnginePicker.EngineAvailability availability) {
        MarkerLibrary safeLibrary = library;
        try {
            if (safeLibrary == null) safeLibrary = MarkerLibraryIO.loadBundled();
        } catch (IOException e) {
            IJ.log("WARNING: Could not load bundled marker library: " + e.getMessage());
            safeLibrary = new MarkerLibrary(1, Collections.<String>emptyList(), Collections.<MarkerLibrary.Entry>emptyList());
        }

        int channels = channelCount(info, answers);
        DerivedConfig out = new DerivedConfig(channels);
        Map<Integer, MarkerLibrary.Entry> autoDetected = autoDetectMarkers(info, safeLibrary);

        for (int c = 0; c < channels; c++) {
            String markerId = answerString(answers, markerKey(c), null);
            if (markerId == null && autoDetected.containsKey(Integer.valueOf(c))) {
                markerId = autoDetected.get(Integer.valueOf(c)).getId();
                out.autoDetected[c] = true;
            }
            if (markerId == null || markerId.trim().isEmpty()) {
                markerId = MARKER_CUSTOM;
            }

            if (MARKER_CUSTOM.equals(markerId)) {
                out.manual[c] = true;
                out.names.add("Channel" + (c + 1));
                out.colors.add("Grays");
                out.objectThresholds.add("default");
                out.sizes.add("100-Infinity");
                out.minmax.add("None");
                out.intensityThresholds.add("default");
                out.filterPresets.add("Default");
                out.segmentationMethods.add("classical");
                out.markerIds.add(markerId);
                out.markerShapes.add("");
                out.markerCrowdingSensitive.add(Boolean.FALSE);
                continue;
            }

            MarkerLibrary.Entry entry = safeLibrary.byId(markerId);
            if (entry == null && MARKER_AUTOFLUORESCENCE.equals(markerId)) {
                entry = autofluorescenceEntry();
            }
            if (entry == null) {
                // Free-form / unmatched marker name — use the typed text as the channel name,
                // and apply sensible defaults for the rest.
                out.manual[c] = true;
                out.names.add(markerId);
                out.colors.add("Grays");
                out.objectThresholds.add("default");
                out.sizes.add("100-Infinity");
                out.minmax.add("None");
                out.intensityThresholds.add("default");
                out.filterPresets.add("Default");
                out.segmentationMethods.add("classical");
                out.markerIds.add(markerId);
                out.markerShapes.add("");
                out.markerCrowdingSensitive.add(Boolean.FALSE);
                continue;
            }

            String signal = answerString(answers, signalKey(c), SIGNAL_TYPICAL);
            String crowding = answerString(answers, crowdingKey(c), entry.isCrowdingSensitive() ? CROWDING_SPARSE : "");
            out.names.add(simplifiedName(entry));
            out.colors.add(emptyTo(entry.getConventionalLUT(), "Grays"));
            out.objectThresholds.add(formatNumber(baseObjectThreshold(entry) * objectSignalFactor(signal)));
            out.sizes.add(sizeRange(entry));
            out.minmax.add(displayRangeToken(info, entry, signal));
            out.intensityThresholds.add(formatNumber(baseIntensityThreshold(entry) * objectSignalFactor(signal)));
            out.filterPresets.add(emptyTo(entry.getFilterPreset(), "Default"));
            out.segmentationMethods.add(segmentationMethod(entry, crowding, availability));
            out.markerIds.add(entry.getId());
            out.markerShapes.add(entry.getShape());
            out.markerCrowdingSensitive.add(Boolean.valueOf(entry.isCrowdingSensitive()));
        }

        applyZSlice(info, answers, out);
        return out;
    }

    public static Map<Integer, MarkerLibrary.Entry> autoDetectMarkers(MetadataDiagnostics.SeriesInfo info,
                                                                      MarkerLibrary library) {
        Map<Integer, MarkerLibrary.Entry> out = new LinkedHashMap<Integer, MarkerLibrary.Entry>();
        if (info == null || library == null) return out;

        String containerName = safe(info.file);
        if (containerName.trim().isEmpty()) return out;

        List<AutoMarkerMatch> matches = new ArrayList<AutoMarkerMatch>();
        addAutoMatch(matches, library, containerName, "nuclei_dapi", "dapi");
        addAutoMatch(matches, library, containerName, "nuclei_hoechst", "hoechst", "33342", "33258");
        addAutoMatch(matches, library, containerName, "microglia_iba1", "iba1", "iba-1", "aif1");
        addAutoMatch(matches, library, containerName, "microglia_tmem119", "tmem119");
        addAutoMatch(matches, library, containerName, "microglia_p2ry12", "p2ry12", "p2y12");
        addAutoMatch(matches, library, containerName, "astrocytes_gfap", "gfap");
        addAutoMatch(matches, library, containerName, "neurons_neun", "neun", "rbfox3");
        addAutoMatch(matches, library, containerName, "amyloid_h31l21", "h31l21");
        addAutoMatch(matches, library, containerName, "amyloid_abeta_pan", "abeta", "a-beta", "a beta",
                "amyloidbeta", "amyloid beta", "6e10", "4g8");
        addAutoMatch(matches, library, containerName, "synapse_syp", "syp", "sy38", "synaptophysin");
        addAutoMatch(matches, library, containerName, "synapse_psd95", "psd95", "psd-95", "dlg4");

        Collections.sort(matches, new Comparator<AutoMarkerMatch>() {
            @Override public int compare(AutoMarkerMatch left, AutoMarkerMatch right) {
                if (left.position != right.position) {
                    return left.position - right.position;
                }
                return left.priority - right.priority;
            }
        });

        for (int i = 0; i < matches.size() && i < Math.max(0, info.sizeC); i++) {
            out.put(Integer.valueOf(i), matches.get(i).entry);
        }
        return out;
    }

    public static MetadataDiagnostics.SeriesInfo firstSeriesInfo(String directory) {
        try {
            List<MetadataDiagnostics.SeriesInfo> all = MetadataDiagnostics.scanDirectory(directory);
            return all.isEmpty() ? null : all.get(0);
        } catch (RuntimeException e) {
            IJ.log("WARNING: Could not read image metadata for Channel Setup Helper: " + e.getMessage());
            return null;
        }
    }

    private static void applyZSlice(MetadataDiagnostics.SeriesInfo info,
                                    Map<String, Object> answers,
                                    DerivedConfig out) {
        int sizeZ = info == null ? 0 : info.sizeZ;
        String mode = answerString(answers, "zSliceMode", "Whole stack");
        if (sizeZ > 20 && "Middle 40%".equals(mode)) {
            int start = Math.max(1, (int) Math.round(sizeZ * 0.3));
            int end = Math.max(start, (int) Math.round(sizeZ * 0.7));
            out.zSliceMode = ZSliceMode.PER_IMAGE;
            out.zSliceSelections.put(Integer.valueOf(0),
                    new ZSliceSelection(0, safe(info.imageName), sizeZ, new ZSliceRange(start, end)));
        }
    }

    private static String segmentationMethod(MarkerLibrary.Entry entry,
                                             String crowding,
                                             SegmentationEnginePicker.EngineAvailability availability) {
        SegmentationEnginePicker.Engine engine = SegmentationEnginePicker.pick(
                entry.getShape(), entry.isCrowdingSensitive(), normalizeCrowding(crowding), availability);
        if (engine == SegmentationEnginePicker.Engine.StarDist) {
            return "stardist:0.5:0.4";
        }
        if (engine == SegmentationEnginePicker.Engine.Cellpose) {
            return "cellpose:" + BinConfig.DEFAULT_CELLPOSE_DIAMETER
                    + ":" + BinConfig.DEFAULT_CELLPOSE_MODEL
                    + ":" + BinConfig.DEFAULT_CELLPOSE_FLOW_THRESHOLD
                    + ":" + BinConfig.DEFAULT_CELLPOSE_CELLPROB_THRESHOLD
                    + ":gpu=" + BinConfig.DEFAULT_CELLPOSE_USE_GPU;
        }
        return "classical";
    }

    private static String normalizeCrowding(String crowding) {
        if (crowding == null) return "";
        String value = crowding.toLowerCase(Locale.ROOT);
        if (value.contains("crowd")) return "crowded";
        if (value.contains("sparse")) return "sparse";
        return value;
    }

    private static double baseObjectThreshold(MarkerLibrary.Entry entry) {
        String hint = entry.getParticleSizeHint();
        if ("tiny".equals(hint)) return 3.0;
        if ("large".equals(hint)) return 10.0;
        return 5.0;
    }

    private static double baseIntensityThreshold(MarkerLibrary.Entry entry) {
        String level = entry.getIntensityLevel();
        if ("dim".equals(level)) return 5.0;
        if ("bright".equals(level)) return 20.0;
        return 10.0;
    }

    private static double objectSignalFactor(String signal) {
        if (signal == null) return 1.0;
        String value = signal.toLowerCase(Locale.ROOT);
        if (value.contains("dim") || value.contains("faint")) return 0.5;
        if (value.contains("bright")) return 1.3;
        return 1.0;
    }

    private static String displayRangeToken(MetadataDiagnostics.SeriesInfo info,
                                            MarkerLibrary.Entry entry,
                                            String signal) {
        int max = "uint8".equalsIgnoreCase(info == null ? null : info.pixelType) ? 255 : 65535;
        double baseline = "bright".equals(entry.getIntensityLevel()) ? 250.0
                : ("dim".equals(entry.getIntensityLevel()) ? 40.0 : 100.0);
        double factor = signal == null ? 1.0
                : (signal.toLowerCase(Locale.ROOT).contains("dim") ? 0.3
                : (signal.toLowerCase(Locale.ROOT).contains("bright") ? 1.7 : 1.0));
        return formatNumber(baseline * factor) + "-" + max;
    }

    private static String sizeRange(MarkerLibrary.Entry entry) {
        String hint = entry.getParticleSizeHint();
        if ("tiny".equals(hint)) return "5-500";
        if ("small".equals(hint)) return "20-5000";
        if ("large".equals(hint)) return "500-250000";
        return "100-50000";
    }

    private static String simplifiedName(MarkerLibrary.Entry entry) {
        String display = entry.getDisplayName();
        if (display == null || display.trim().isEmpty()) return "Channel";
        return display.replaceAll("^.*\\((.*)\\).*$", "$1").trim();
    }

    private static int channelCount(MetadataDiagnostics.SeriesInfo info, Map<String, Object> answers) {
        int fromAnswers = intAnswer(answers, "channelCount", 0);
        if (fromAnswers > 0) return fromAnswers;
        if (info != null && info.sizeC > 0) return info.sizeC;
        return 3;
    }

    private static MarkerLibrary.Entry autofluorescenceEntry() {
        return new MarkerLibrary.Entry(
                MARKER_AUTOFLUORESCENCE,
                "Autofluorescence channel (no target)",
                Collections.<String>emptyList(),
                "special",
                "autofluorescence",
                "diffuse background",
                "Grays",
                "Default",
                "diffuse",
                false,
                "large",
                "dim",
                Collections.<String>emptyList(),
                "No target marker.");
    }

    private static void addAutoMatch(List<AutoMarkerMatch> matches,
                                     MarkerLibrary library,
                                     String containerName,
                                     String markerId,
                                     String... tokens) {
        MarkerLibrary.Entry entry = library == null ? null : library.byId(markerId);
        if (entry == null) return;
        int position = firstTokenPosition(containerName, tokens);
        if (position >= 0) {
            matches.add(new AutoMarkerMatch(entry, position, matches.size()));
        }
    }

    private static int firstTokenPosition(String containerName, String... tokens) {
        String haystack = normalizeForAutoDetection(containerName);
        int best = -1;
        if (tokens == null) return best;
        for (String token : tokens) {
            String needle = normalizeForAutoDetection(token);
            if (needle.length() < 3) continue;
            int index = haystack.indexOf(needle);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private static String normalizeForAutoDetection(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String markerKey(int channelIndex) {
        return "channel" + (channelIndex + 1) + ".markerId";
    }

    private static String signalKey(int channelIndex) {
        return "channel" + (channelIndex + 1) + ".signal";
    }

    private static String crowdingKey(int channelIndex) {
        return "channel" + (channelIndex + 1) + ".crowding";
    }

    private static String answerString(Map<String, Object> answers, String key, String fallback) {
        if (answers == null) return fallback;
        Object value = answers.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static int intAnswer(Map<String, Object> answers, String key, int fallback) {
        String raw = answerString(answers, key, null);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static final class AutoMarkerMatch {
        private final MarkerLibrary.Entry entry;
        private final int position;
        private final int priority;

        private AutoMarkerMatch(MarkerLibrary.Entry entry, int position, int priority) {
            this.entry = entry;
            this.position = position;
            this.priority = priority;
        }
    }

    public static final class DerivedConfig {
        public final List<String> names = new ArrayList<String>();
        public final List<String> colors = new ArrayList<String>();
        public final List<String> objectThresholds = new ArrayList<String>();
        public final List<String> sizes = new ArrayList<String>();
        public final List<String> minmax = new ArrayList<String>();
        public final List<String> filterPresets = new ArrayList<String>();
        public final List<String> intensityThresholds = new ArrayList<String>();
        public final List<String> segmentationMethods = new ArrayList<String>();
        public final List<String> markerIds = new ArrayList<String>();
        public final List<String> markerShapes = new ArrayList<String>();
        public final List<Boolean> markerCrowdingSensitive = new ArrayList<Boolean>();
        public final boolean[] autoDetected;
        public final boolean[] manual;
        public ZSliceMode zSliceMode = ZSliceMode.FULL;
        public final LinkedHashMap<Integer, ZSliceSelection> zSliceSelections =
                new LinkedHashMap<Integer, ZSliceSelection>();

        private DerivedConfig(int channels) {
            this.autoDetected = new boolean[channels];
            this.manual = new boolean[channels];
        }

        public BinConfig toBinConfig() {
            BinConfig cfg = new BinConfig();
            cfg.channelNames.addAll(names);
            cfg.channelColors.addAll(colors);
            cfg.channelThresholds.addAll(objectThresholds);
            cfg.channelSizes.addAll(sizes);
            cfg.channelMinMax.addAll(minmax);
            cfg.channelIntensityThresholds.addAll(intensityThresholds);
            cfg.channelFilterPresets.addAll(filterPresets);
            cfg.segmentationMethods.addAll(segmentationMethods);
            cfg.zSliceMode = zSliceMode;
            cfg.zSliceSelections.putAll(zSliceSelections);
            return cfg;
        }
    }

    private final class MarkerScreen extends Screen {
        private final List<MarkerTypeahead> markerFields = new ArrayList<MarkerTypeahead>();

        private MarkerScreen() {
            super("What did you stain for in each channel?");
            int channels = channelCount(seriesInfo, Collections.<String, Object>emptyMap());
            defaultAnswer("channelCount", Integer.valueOf(channels));
            Map<Integer, MarkerLibrary.Entry> detected = autoDetectMarkers(seriesInfo, library);
            for (int i = 0; i < channels; i++) {
                MarkerLibrary.Entry entry = detected.get(Integer.valueOf(i));
                defaultAnswer(markerKey(i), entry == null ? "" : entry.getId());
            }
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("What did you stain for in each channel?");
            dialog.addMessage("Type the marker name (e.g. DAPI, IBA1, GFAP, NeuN, PSD95). "
                    + "Suggestions appear as you type, but any text is accepted.");
            markerFields.clear();
            flash.pipeline.ui.wizard.MarkerLibrary typeaheadLibrary = toTypeaheadLibrary(library);
            for (int i = 0; i < answers.getInt("channelCount", 3); i++) {
                MarkerTypeahead typeahead = new MarkerTypeahead(typeaheadLibrary);
                String selectedId = answers.getString(markerKey(i), "");
                flash.pipeline.ui.wizard.MarkerLibrary.Entry selected = typeaheadEntryById(typeaheadLibrary, selectedId);
                if (selected != null) {
                    typeahead.setSelectedMarker(selected);
                } else {
                    typeahead.getTextField().setText(selectedId);
                }
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                row.add(new JLabel("Channel " + (i + 1) + " marker"), BorderLayout.WEST);
                row.add(typeahead, BorderLayout.CENTER);
                dialog.addComponent(row);
                markerFields.add(typeahead);
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            for (int i = 0; i < answers.getInt("channelCount", 3); i++) {
                MarkerTypeahead typeahead = markerFields.get(i);
                flash.pipeline.ui.wizard.MarkerLibrary.Entry selected = typeahead.getSelectedMarker();
                String typed = selected == null ? typeahead.getTextField().getText() : selected.getId();
                answers.put(markerKey(i), resolveTypedMarker(typed));
            }
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            writeDerivedConfig(panel, answers);
        }
    }

    private final class SignalScreen extends Screen {
        private SignalScreen() {
            super("How bright is each channel typically?");
        }

        public boolean isApplicable(AnswerMap prior) {
            return true;
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("How bright is each channel typically?");
            String[] choices = {SIGNAL_DIM, SIGNAL_TYPICAL, SIGNAL_BRIGHT};
            for (int i = 0; i < answers.getInt("channelCount", 3); i++) {
                dialog.addChoice("Channel " + (i + 1), choices, answers.getString(signalKey(i), SIGNAL_TYPICAL));
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            for (int i = 0; i < answers.getInt("channelCount", 3); i++) {
                answers.put(signalKey(i), dialog.getNextChoice());
            }
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class CrowdingScreen extends Screen {
        private CrowdingScreen() {
            super("How crowded are these objects in your tissue?");
        }

        public boolean isApplicable(AnswerMap prior) {
            for (int i = 0; i < prior.getInt("channelCount", 3); i++) {
                MarkerLibrary.Entry entry = library.byId(prior.getString(markerKey(i), ""));
                if (entry != null && entry.isCrowdingSensitive()) return true;
            }
            return false;
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("How crowded are these objects in your tissue?");
            String[] choices = {CROWDING_SPARSE, CROWDING_CROWDED};
            for (int i = 0; i < answers.getInt("channelCount", 3); i++) {
                MarkerLibrary.Entry entry = library.byId(answers.getString(markerKey(i), ""));
                if (entry != null && entry.isCrowdingSensitive()) {
                    dialog.addChoice("Channel " + (i + 1), choices, answers.getString(crowdingKey(i), CROWDING_SPARSE));
                }
            }
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            for (int i = 0; i < answers.getInt("channelCount", 3); i++) {
                MarkerLibrary.Entry entry = library.byId(answers.getString(markerKey(i), ""));
                if (entry != null && entry.isCrowdingSensitive()) {
                    answers.put(crowdingKey(i), dialog.getNextChoice());
                }
            }
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class ZSliceScreen extends Screen {
        private ZSliceScreen() {
            super("Analyse the whole z-stack, or just a representative slab?");
            defaultAnswer("zSliceMode", "Whole stack");
        }

        public boolean isApplicable(AnswerMap prior) {
            return seriesInfo != null && seriesInfo.sizeZ > 20;
        }

        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Analyse the whole z-stack, or just a representative slab?");
            dialog.addChoice("Z-slice range",
                    new String[]{"Whole stack", "Middle 40%", "Custom range"},
                    answers.getString("zSliceMode", "Whole stack"));
        }

        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("zSliceMode", dialog.getNextChoice());
        }

        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            DerivedConfig config = deriveConfig(seriesInfo, answers, library, availability);
            panel.setValue("zSlice.config", config);
        }
    }

    private void writeDerivedConfig(MainPanelBinding panel, AnswerMap answers) {
        DerivedConfig config = deriveConfig(seriesInfo, answers, library, availability);
        for (int i = 0; i < config.names.size(); i++) {
            panel.setValue("channel" + (i + 1), config);
            panel.markRecommended("channel" + (i + 1));
        }
    }

    private String resolveTypedMarker(String typed) {
        // Free-form input: any non-empty string is accepted.
        // - empty -> MARKER_CUSTOM (signals "no marker chosen yet")
        // - exact library id match -> that id (so saved bins reload deterministically)
        // - exact displayName/alias match -> that id (typeahead-confirmed selection)
        // - anything else -> the typed text itself (downstream uses it as the channel name)
        if (typed == null || typed.trim().isEmpty()) return MARKER_CUSTOM;
        String value = typed.trim();
        if (MARKER_CUSTOM.equals(value) || MARKER_AUTOFLUORESCENCE.equals(value)) return value;
        MarkerLibrary.Entry byId = library.byId(value);
        if (byId != null) return byId.getId();
        for (MarkerLibrary.Entry entry : library.entries()) {
            if (value.equalsIgnoreCase(entry.getDisplayName())) return entry.getId();
            for (String alias : entry.getAliases()) {
                if (value.equalsIgnoreCase(alias)) return entry.getId();
            }
        }
        return value;
    }

    private static flash.pipeline.ui.wizard.MarkerLibrary toTypeaheadLibrary(MarkerLibrary source) {
        List<flash.pipeline.ui.wizard.MarkerLibrary.Entry> entries =
                new ArrayList<flash.pipeline.ui.wizard.MarkerLibrary.Entry>();
        if (source != null) {
            for (MarkerLibrary.Entry entry : source.entries()) {
                entries.add(new flash.pipeline.ui.wizard.MarkerLibrary.Entry(
                        entry.getId(),
                        entry.getDisplayName(),
                        entry.getAliases(),
                        entry.getNameHints(),
                        entry.getCategory(),
                        entry.getAdditionalCategories(),
                        entry.getShape(),
                        entry.isCrowdingSensitive(),
                        entry.isCrowdingSensitive()));
            }
        }
        return new flash.pipeline.ui.wizard.MarkerLibrary(entries);
    }

    private static flash.pipeline.ui.wizard.MarkerLibrary.Entry typeaheadEntryById(
            flash.pipeline.ui.wizard.MarkerLibrary library, String id) {
        if (library == null || id == null) return null;
        for (flash.pipeline.ui.wizard.MarkerLibrary.Entry entry : library.entries()) {
            if (id.equals(entry.getId())) {
                return entry;
            }
        }
        return null;
    }
}
