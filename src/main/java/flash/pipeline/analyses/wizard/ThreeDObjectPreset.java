package flash.pipeline.analyses.wizard;

import flash.pipeline.objects.OipConfig;
import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted setup for 3D Object Analysis.
 *
 * <p>Schema 2 has two deliberately distinct forms. A captured configuration has one
 * {@link ChannelSetting} for every channel and therefore reproduces heterogeneous channel state.
 * A template has no bound channels and carries explicit defaults; this is the canonical migration
 * form for stock and schema-1 scalar presets.</p>
 */
public final class ThreeDObjectPreset implements Preset<ThreeDObjectPreset> {

    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final String CURRENT_LIBRARY_VERSION = "1";
    public static final boolean DEFAULT_DO_RADIAL_PROFILE = true;
    public static final boolean DEFAULT_DO_MARGINAL_PROFILE = true;
    public static final boolean DEFAULT_DO_PRINCIPAL_AXIS_PROFILE = true;
    public static final boolean DEFAULT_DO_ANGULAR_PROFILE = false;
    public static final boolean DEFAULT_DO_SHELL_COLOC = false;
    public static final boolean DEFAULT_DO_WITHIN_BOX_CORR = false;
    public static final boolean DEFAULT_OIP_GENERATE_FIGURES = true;
    public static final String DEFAULT_OIP_REGION = OipConfig.Region.WHOLE_BOX.name();
    public static final String DEFAULT_OIP_INTENSITY_NORM =
            OipConfig.IntensityNorm.PER_OBJECT_MINMAX.name();
    public static final int DEFAULT_OIP_RADIAL_BINS = 20;
    public static final int DEFAULT_OIP_ANGULAR_BINS = 12;
    public static final int DEFAULT_OIP_SHELLS = 3;
    public static final int DEFAULT_OIP_RESAMPLE_N = 50;
    public static final double DEFAULT_OIP_BOX_PAD_PCT = 0.0;
    public static final double DEFAULT_OIP_RING_THRESHOLD_PCT = 50.0;

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final boolean doVolumetric;
    private final boolean doCpc;
    private final boolean doIntensityColoc;
    private final boolean extractProcessLength;
    private final boolean runSpatial;
    private final boolean classicalCentroidFiltering;
    private final boolean doBBOverlap;
    private final boolean doBBCpc;
    private final boolean doBBVol;
    private final Map<String, ChannelSetting> channelSettings;
    private final ChannelDefaults channelDefaults;
    private final boolean doRadialProfile;
    private final boolean doMarginalProfile;
    private final boolean doPrincipalAxisProfile;
    private final boolean doAngularProfile;
    private final boolean doShellColoc;
    private final boolean doWithinBoxCorr;
    private final boolean oipGenerateFigures;
    private final String oipRegion;
    private final String oipIntensityNorm;
    private final int oipRadialBins;
    private final int oipAngularBins;
    private final int oipShells;
    private final int oipResampleN;
    private final double oipBoxPadPct;
    private final double oipRingThresholdPct;

    public ThreeDObjectPreset(String name,
                              String description,
                              String libraryVersion,
                              boolean doVolumetric,
                              boolean doCpc,
                              boolean extractProcessLength,
                              boolean runSpatial,
                              boolean classicalCentroidFiltering,
                              double colocThresholdPercent,
                              List<String> processMarkerHints,
                              List<String> nuclearMarkerHints) {
        this(name, description, libraryVersion, doVolumetric, doCpc, false,
                extractProcessLength, runSpatial, classicalCentroidFiltering,
                colocThresholdPercent, processMarkerHints, nuclearMarkerHints);
    }

    public ThreeDObjectPreset(String name,
                              String description,
                              String libraryVersion,
                              boolean doVolumetric,
                              boolean doCpc,
                              boolean doIntensityColoc,
                              boolean extractProcessLength,
                              boolean runSpatial,
                              boolean classicalCentroidFiltering,
                              double colocThresholdPercent,
                              List<String> processMarkerHints,
                              List<String> nuclearMarkerHints) {
        this(name, description, libraryVersion, doVolumetric, doCpc, doIntensityColoc,
                extractProcessLength, runSpatial, classicalCentroidFiltering, colocThresholdPercent,
                false, false, false, 30.0, processMarkerHints, nuclearMarkerHints);
    }

    public ThreeDObjectPreset(String name,
                              String description,
                              String libraryVersion,
                              boolean doVolumetric,
                              boolean doCpc,
                              boolean doIntensityColoc,
                              boolean extractProcessLength,
                              boolean runSpatial,
                              boolean classicalCentroidFiltering,
                              double colocThresholdPercent,
                              boolean doBBOverlap,
                              boolean doBBCpc,
                              boolean doBBVol,
                              double bbColocThresholdPercent,
                              List<String> processMarkerHints,
                              List<String> nuclearMarkerHints) {
        this(name, description, libraryVersion, doVolumetric, doCpc, doIntensityColoc,
                extractProcessLength, runSpatial, classicalCentroidFiltering, colocThresholdPercent,
                doBBOverlap, doBBCpc, doBBVol, bbColocThresholdPercent,
                processMarkerHints, nuclearMarkerHints,
                DEFAULT_DO_RADIAL_PROFILE,
                DEFAULT_DO_MARGINAL_PROFILE,
                DEFAULT_DO_PRINCIPAL_AXIS_PROFILE,
                DEFAULT_DO_ANGULAR_PROFILE,
                DEFAULT_DO_SHELL_COLOC,
                DEFAULT_DO_WITHIN_BOX_CORR,
                DEFAULT_OIP_GENERATE_FIGURES,
                DEFAULT_OIP_REGION,
                DEFAULT_OIP_INTENSITY_NORM,
                DEFAULT_OIP_RADIAL_BINS,
                DEFAULT_OIP_ANGULAR_BINS,
                DEFAULT_OIP_SHELLS,
                DEFAULT_OIP_RESAMPLE_N,
                DEFAULT_OIP_BOX_PAD_PCT,
                DEFAULT_OIP_RING_THRESHOLD_PCT);
    }

    public ThreeDObjectPreset(String name,
                              String description,
                              String libraryVersion,
                              boolean doVolumetric,
                              boolean doCpc,
                              boolean doIntensityColoc,
                              boolean extractProcessLength,
                              boolean runSpatial,
                              boolean classicalCentroidFiltering,
                              double colocThresholdPercent,
                              boolean doBBOverlap,
                              boolean doBBCpc,
                              boolean doBBVol,
                              double bbColocThresholdPercent,
                              List<String> processMarkerHints,
                              List<String> nuclearMarkerHints,
                              boolean doRadialProfile,
                              boolean doMarginalProfile,
                              boolean doPrincipalAxisProfile,
                              boolean doAngularProfile,
                              boolean doShellColoc,
                              boolean doWithinBoxCorr,
                              boolean oipGenerateFigures,
                              String oipRegion,
                              String oipIntensityNorm,
                              int oipRadialBins,
                              int oipAngularBins,
                              int oipShells,
                              int oipResampleN,
                              double oipBoxPadPct,
                              double oipRingThresholdPct) {
        this(name, description, libraryVersion, doVolumetric, doCpc, doIntensityColoc,
                extractProcessLength, runSpatial, classicalCentroidFiltering,
                doBBOverlap, doBBCpc, doBBVol,
                Collections.<String, ChannelSetting>emptyMap(),
                new ChannelDefaults(colocThresholdPercent, bbColocThresholdPercent,
                        processMarkerHints, nuclearMarkerHints),
                doRadialProfile, doMarginalProfile, doPrincipalAxisProfile, doAngularProfile,
                doShellColoc, doWithinBoxCorr, oipGenerateFigures, oipRegion, oipIntensityNorm,
                oipRadialBins, oipAngularBins, oipShells, oipResampleN,
                oipBoxPadPct, oipRingThresholdPct);
    }

    /**
     * Complete schema-2 constructor used by GUI capture, replay capture, and typed callers.
     */
    public ThreeDObjectPreset(String name,
                              String description,
                              String libraryVersion,
                              boolean doVolumetric,
                              boolean doCpc,
                              boolean doIntensityColoc,
                              boolean extractProcessLength,
                              boolean runSpatial,
                              boolean classicalCentroidFiltering,
                              boolean doBBOverlap,
                              boolean doBBCpc,
                              boolean doBBVol,
                              Map<String, ChannelSetting> channelSettings,
                              boolean doRadialProfile,
                              boolean doMarginalProfile,
                              boolean doPrincipalAxisProfile,
                              boolean doAngularProfile,
                              boolean doShellColoc,
                              boolean doWithinBoxCorr,
                              boolean oipGenerateFigures,
                              String oipRegion,
                              String oipIntensityNorm,
                              int oipRadialBins,
                              int oipAngularBins,
                              int oipShells,
                              int oipResampleN,
                              double oipBoxPadPct,
                              double oipRingThresholdPct) {
        this(name, description, libraryVersion, doVolumetric, doCpc, doIntensityColoc,
                extractProcessLength, runSpatial, classicalCentroidFiltering,
                doBBOverlap, doBBCpc, doBBVol, channelSettings, null,
                doRadialProfile, doMarginalProfile, doPrincipalAxisProfile, doAngularProfile,
                doShellColoc, doWithinBoxCorr, oipGenerateFigures, oipRegion, oipIntensityNorm,
                oipRadialBins, oipAngularBins, oipShells, oipResampleN,
                oipBoxPadPct, oipRingThresholdPct);
    }

    private ThreeDObjectPreset(String name,
                               String description,
                               String libraryVersion,
                               boolean doVolumetric,
                               boolean doCpc,
                               boolean doIntensityColoc,
                               boolean extractProcessLength,
                               boolean runSpatial,
                               boolean classicalCentroidFiltering,
                               boolean doBBOverlap,
                               boolean doBBCpc,
                               boolean doBBVol,
                               Map<String, ChannelSetting> channelSettings,
                               ChannelDefaults channelDefaults,
                               boolean doRadialProfile,
                               boolean doMarginalProfile,
                               boolean doPrincipalAxisProfile,
                               boolean doAngularProfile,
                               boolean doShellColoc,
                               boolean doWithinBoxCorr,
                               boolean oipGenerateFigures,
                               String oipRegion,
                               String oipIntensityNorm,
                               int oipRadialBins,
                               int oipAngularBins,
                               int oipShells,
                               int oipResampleN,
                               double oipBoxPadPct,
                               double oipRingThresholdPct) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = emptyToNull(libraryVersion) == null
                ? CURRENT_LIBRARY_VERSION : libraryVersion.trim();
        this.doVolumetric = doVolumetric;
        this.doCpc = doCpc;
        this.doIntensityColoc = doIntensityColoc;
        this.extractProcessLength = extractProcessLength;
        this.runSpatial = runSpatial;
        this.classicalCentroidFiltering = classicalCentroidFiltering;
        this.doBBOverlap = doBBOverlap;
        this.doBBCpc = doBBCpc;
        this.doBBVol = doBBVol;
        this.channelSettings = immutableChannelSettings(channelSettings);
        this.channelDefaults = channelDefaults;
        if (this.channelSettings.isEmpty() == (this.channelDefaults == null)) {
            throw new IllegalArgumentException(
                    "Preset must contain either bound channel settings or channel defaults.");
        }
        validateChannelRoles(this.channelSettings, extractProcessLength);
        this.doRadialProfile = doRadialProfile;
        this.doMarginalProfile = doMarginalProfile;
        this.doPrincipalAxisProfile = doPrincipalAxisProfile;
        this.doAngularProfile = doAngularProfile;
        this.doShellColoc = doShellColoc;
        this.doWithinBoxCorr = doWithinBoxCorr;
        this.oipGenerateFigures = oipGenerateFigures;
        this.oipRegion = normalizeOipRegion(oipRegion);
        this.oipIntensityNorm = normalizeOipIntensityNorm(oipIntensityNorm);
        this.oipRadialBins = requireAtLeast("oipRadialBins", oipRadialBins, 1);
        this.oipAngularBins = requireAtLeast("oipAngularBins", oipAngularBins, 1);
        this.oipShells = requireAtLeast("oipShells", oipShells, 1);
        this.oipResampleN = requireAtLeast("oipResampleN", oipResampleN, 2);
        this.oipBoxPadPct = requireFiniteAtLeast("oipBoxPadPct", oipBoxPadPct, 0.0);
        this.oipRingThresholdPct = requirePercent("oipRingThresholdPct", oipRingThresholdPct);
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLibraryVersion() { return libraryVersion; }
    public ThreeDObjectPreset getPayload() { return this; }
    public boolean isDoVolumetric() { return doVolumetric; }
    public boolean isDoCpc() { return doCpc; }
    public boolean isDoIntensityColoc() { return doIntensityColoc; }
    public boolean isExtractProcessLength() { return extractProcessLength; }
    public boolean isRunSpatial() { return runSpatial; }
    public boolean isClassicalCentroidFiltering() { return classicalCentroidFiltering; }
    public boolean isDoBBOverlap() { return doBBOverlap; }
    public boolean isDoBBCpc() { return doBBCpc; }
    public boolean isDoBBVol() { return doBBVol; }
    public boolean hasBoundChannelSettings() { return !channelSettings.isEmpty(); }
    public Map<String, ChannelSetting> getChannelSettings() { return channelSettings; }
    public boolean isDoRadialProfile() { return doRadialProfile; }
    public boolean isDoMarginalProfile() { return doMarginalProfile; }
    public boolean isDoPrincipalAxisProfile() { return doPrincipalAxisProfile; }
    public boolean isDoAngularProfile() { return doAngularProfile; }
    public boolean isDoShellColoc() { return doShellColoc; }
    public boolean isDoWithinBoxCorr() { return doWithinBoxCorr; }
    public boolean isOipGenerateFigures() { return oipGenerateFigures; }
    public String getOipRegion() { return oipRegion; }
    public String getOipIntensityNorm() { return oipIntensityNorm; }
    public int getOipRadialBins() { return oipRadialBins; }
    public int getOipAngularBins() { return oipAngularBins; }
    public int getOipShells() { return oipShells; }
    public int getOipResampleN() { return oipResampleN; }
    public double getOipBoxPadPct() { return oipBoxPadPct; }
    public double getOipRingThresholdPct() { return oipRingThresholdPct; }

    /**
     * Copy this preset while replacing only Object Intensity Profiling options.
     * Bound channel settings and template defaults retain their original representation.
     */
    public ThreeDObjectPreset withOipOptions(boolean radialProfile,
                                             boolean marginalProfile,
                                             boolean principalAxisProfile,
                                             boolean angularProfile,
                                             boolean shellColoc,
                                             boolean withinBoxCorr,
                                             boolean generateFigures,
                                             String region,
                                             String intensityNorm,
                                             int radialBins,
                                             int angularBins,
                                             int shells,
                                             int resampleN,
                                             double boxPadPct,
                                             double ringThresholdPct) {
        return new ThreeDObjectPreset(
                name, description, libraryVersion,
                doVolumetric, doCpc, doIntensityColoc,
                extractProcessLength, runSpatial, classicalCentroidFiltering,
                doBBOverlap, doBBCpc, doBBVol,
                channelSettings, channelDefaults,
                radialProfile, marginalProfile, principalAxisProfile, angularProfile,
                shellColoc, withinBoxCorr, generateFigures, region, intensityNorm,
                radialBins, angularBins, shells, resampleN, boxPadPct, ringThresholdPct);
    }

    /** Compatibility getters for template/legacy callers. Bound presets return their first key. */
    public double getColocThresholdPercent() {
        if (channelDefaults != null) return channelDefaults.colocThresholdPercent;
        return channelSettings.values().iterator().next().getColocThresholdPercent();
    }

    public double getBBColocThresholdPercent() {
        if (channelDefaults != null) return channelDefaults.bbColocThresholdPercent;
        return channelSettings.values().iterator().next().getBBColocThresholdPercent();
    }

    public List<String> getProcessMarkerHints() {
        return channelDefaults == null ? Collections.<String>emptyList()
                : channelDefaults.processMarkerHints;
    }

    public List<String> getNuclearMarkerHints() {
        return channelDefaults == null ? Collections.<String>emptyList()
                : channelDefaults.nuclearMarkerHints;
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", Integer.valueOf(CURRENT_SCHEMA_VERSION));
        root.put("name", name);
        if (description != null) root.put("description", description);
        root.put("libraryVersion", libraryVersion);
        root.put("doVolumetric", Boolean.valueOf(doVolumetric));
        root.put("doCpc", Boolean.valueOf(doCpc));
        root.put("doIntensityColoc", Boolean.valueOf(doIntensityColoc));
        root.put("extractProcessLength", Boolean.valueOf(extractProcessLength));
        root.put("runSpatial", Boolean.valueOf(runSpatial));
        root.put("classicalCentroidFiltering", Boolean.valueOf(classicalCentroidFiltering));
        root.put("doBBOverlap", Boolean.valueOf(doBBOverlap));
        root.put("doBBCpc", Boolean.valueOf(doBBCpc));
        root.put("doBBVol", Boolean.valueOf(doBBVol));
        Map<String, Object> channels = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ChannelSetting> entry : channelSettings.entrySet()) {
            channels.put(entry.getKey(), entry.getValue().toJsonObject());
        }
        root.put("channelSettings", channels);
        if (channelDefaults != null) root.put("channelDefaults", channelDefaults.toJsonObject());
        root.put("doRadialProfile", Boolean.valueOf(doRadialProfile));
        root.put("doMarginalProfile", Boolean.valueOf(doMarginalProfile));
        root.put("doPrincipalAxisProfile", Boolean.valueOf(doPrincipalAxisProfile));
        root.put("doAngularProfile", Boolean.valueOf(doAngularProfile));
        root.put("doShellColoc", Boolean.valueOf(doShellColoc));
        root.put("doWithinBoxCorr", Boolean.valueOf(doWithinBoxCorr));
        root.put("oipGenerateFigures", Boolean.valueOf(oipGenerateFigures));
        root.put("oipRegion", oipRegion);
        root.put("oipIntensityNorm", oipIntensityNorm);
        root.put("oipRadialBins", Integer.valueOf(oipRadialBins));
        root.put("oipAngularBins", Integer.valueOf(oipAngularBins));
        root.put("oipShells", Integer.valueOf(oipShells));
        root.put("oipResampleN", Integer.valueOf(oipResampleN));
        root.put("oipBoxPadPct", Double.valueOf(oipBoxPadPct));
        root.put("oipRingThresholdPct", Double.valueOf(oipRingThresholdPct));
        return root;
    }

    public String toJson() { return JsonIO.write(toJsonObject()); }

    public static ThreeDObjectPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static ThreeDObjectPreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) throw new IOException("Preset JSON object is required.");
        Object schemaValue = root.get("schemaVersion");
        if (schemaValue == null) {
            rejectLegacySchemaTwoFields(root);
            return readLegacyChecked(root);
        }
        int schema = requiredInteger("schemaVersion", schemaValue);
        if (schema == 1) {
            rejectLegacySchemaTwoFields(root);
            return readLegacyChecked(root);
        }
        if (schema != CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported 3D Object preset schemaVersion " + schema
                    + "; expected " + CURRENT_SCHEMA_VERSION + ".");
        }
        rejectKeys(root, "colocThresholdPercent", "bbColocThresholdPercent",
                "processMarkerHints", "nuclearMarkerHints");
        Map<String, ChannelSetting> settings = readChannelSettings(root.get("channelSettings"));
        ChannelDefaults defaults = root.containsKey("channelDefaults")
                ? ChannelDefaults.fromJson(root.get("channelDefaults")) : null;
        if (settings.isEmpty() == (defaults == null)) {
            throw new IOException(
                    "schemaVersion 2 requires either non-empty channelSettings or channelDefaults.");
        }
        return constructStrict(root, settings, defaults);
    }

    private static void rejectLegacySchemaTwoFields(Map<String, Object> root)
            throws IOException {
        if (root.containsKey("channelSettings") || root.containsKey("channelDefaults")) {
            throw new IOException(
                    "channelSettings/channelDefaults require schemaVersion 2.");
        }
    }

    private static ThreeDObjectPreset readLegacyChecked(Map<String, Object> root)
            throws IOException {
        try {
            return readLegacy(root);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid legacy 3D Object preset: " + e.getMessage(), e);
        }
    }

    private static ThreeDObjectPreset readLegacy(Map<String, Object> root) throws IOException {
        String name = optionalString(root, "name", "3D Object Preset");
        boolean doVolumetric = optionalBoolean(root, "doVolumetric", false);
        boolean doCpc = optionalBoolean(root, "doCpc", false);
        boolean doIntensityColoc = optionalBoolean(root, "doIntensityColoc", false);
        boolean extractProcessLength = optionalBoolean(root, "extractProcessLength", false);
        boolean runSpatial = optionalBoolean(root, "runSpatial", false);
        if (isBuiltInColocPresetName(name) && doVolumetric && doCpc) doIntensityColoc = true;
        if (isBuiltInFullWorkflowPresetName(name)
                && doVolumetric && doCpc && extractProcessLength) {
            doIntensityColoc = true;
            runSpatial = true;
        }
        ChannelDefaults defaults = new ChannelDefaults(
                optionalPercent(root, "colocThresholdPercent", 30.0),
                optionalPercent(root, "bbColocThresholdPercent", 30.0),
                optionalStrings(root, "processMarkerHints"),
                optionalStrings(root, "nuclearMarkerHints"));
        return new ThreeDObjectPreset(
                name,
                optionalNullableString(root, "description"),
                optionalString(root, "libraryVersion", CURRENT_LIBRARY_VERSION),
                doVolumetric, doCpc, doIntensityColoc,
                extractProcessLength, runSpatial,
                optionalBoolean(root, "classicalCentroidFiltering", false),
                optionalBoolean(root, "doBBOverlap", false),
                optionalBoolean(root, "doBBCpc", false),
                optionalBoolean(root, "doBBVol", false),
                Collections.<String, ChannelSetting>emptyMap(), defaults,
                optionalBoolean(root, "doRadialProfile", DEFAULT_DO_RADIAL_PROFILE),
                optionalBoolean(root, "doMarginalProfile", DEFAULT_DO_MARGINAL_PROFILE),
                optionalBoolean(root, "doPrincipalAxisProfile", DEFAULT_DO_PRINCIPAL_AXIS_PROFILE),
                optionalBoolean(root, "doAngularProfile", DEFAULT_DO_ANGULAR_PROFILE),
                optionalBoolean(root, "doShellColoc", DEFAULT_DO_SHELL_COLOC),
                optionalBoolean(root, "doWithinBoxCorr", DEFAULT_DO_WITHIN_BOX_CORR),
                optionalBoolean(root, "oipGenerateFigures", DEFAULT_OIP_GENERATE_FIGURES),
                optionalString(root, "oipRegion", DEFAULT_OIP_REGION),
                optionalString(root, "oipIntensityNorm", DEFAULT_OIP_INTENSITY_NORM),
                optionalInteger(root, "oipRadialBins", DEFAULT_OIP_RADIAL_BINS, 1),
                optionalInteger(root, "oipAngularBins", DEFAULT_OIP_ANGULAR_BINS, 1),
                optionalInteger(root, "oipShells", DEFAULT_OIP_SHELLS, 1),
                optionalInteger(root, "oipResampleN", DEFAULT_OIP_RESAMPLE_N, 2),
                optionalFiniteAtLeast(root, "oipBoxPadPct", DEFAULT_OIP_BOX_PAD_PCT, 0.0),
                optionalPercent(root, "oipRingThresholdPct", DEFAULT_OIP_RING_THRESHOLD_PCT));
    }

    private static ThreeDObjectPreset constructStrict(Map<String, Object> root,
                                                       Map<String, ChannelSetting> settings,
                                                       ChannelDefaults defaults) throws IOException {
        try {
            return new ThreeDObjectPreset(
                    requiredText(root, "name"),
                    optionalNullableString(root, "description"),
                    optionalString(root, "libraryVersion", CURRENT_LIBRARY_VERSION),
                    requiredBoolean(root, "doVolumetric"),
                    requiredBoolean(root, "doCpc"),
                    requiredBoolean(root, "doIntensityColoc"),
                    requiredBoolean(root, "extractProcessLength"),
                    requiredBoolean(root, "runSpatial"),
                    requiredBoolean(root, "classicalCentroidFiltering"),
                    requiredBoolean(root, "doBBOverlap"),
                    requiredBoolean(root, "doBBCpc"),
                    requiredBoolean(root, "doBBVol"),
                    settings, defaults,
                    requiredBoolean(root, "doRadialProfile"),
                    requiredBoolean(root, "doMarginalProfile"),
                    requiredBoolean(root, "doPrincipalAxisProfile"),
                    requiredBoolean(root, "doAngularProfile"),
                    requiredBoolean(root, "doShellColoc"),
                    requiredBoolean(root, "doWithinBoxCorr"),
                    requiredBoolean(root, "oipGenerateFigures"),
                    requiredText(root, "oipRegion"),
                    requiredText(root, "oipIntensityNorm"),
                    requiredIntegerAtLeast(root, "oipRadialBins", 1),
                    requiredIntegerAtLeast(root, "oipAngularBins", 1),
                    requiredIntegerAtLeast(root, "oipShells", 1),
                    requiredIntegerAtLeast(root, "oipResampleN", 2),
                    requiredFiniteAtLeast(root, "oipBoxPadPct", 0.0),
                    requiredPercent(root, "oipRingThresholdPct"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid schemaVersion 2 preset: " + e.getMessage(), e);
        }
    }

    private static Map<String, ChannelSetting> readChannelSettings(Object value) throws IOException {
        if (!(value instanceof Map)) {
            throw new IOException("channelSettings must be a JSON object.");
        }
        Map<?, ?> raw = (Map<?, ?>) value;
        List<String> keys = new ArrayList<String>();
        for (Object key : raw.keySet()) {
            if (!(key instanceof String) || ((String) key).trim().isEmpty()) {
                throw new IOException("channelSettings keys must be non-empty strings.");
            }
            keys.add((String) key);
        }
        Collections.sort(keys);
        Map<String, ChannelSetting> out = new LinkedHashMap<String, ChannelSetting>();
        for (String key : keys) {
            ChannelSetting setting = ChannelSetting.fromJson(key, raw.get(key));
            if (out.put(key, setting) != null) {
                throw new IOException("Duplicate channel identity key '" + key + "'.");
            }
        }
        return out;
    }

    public static String channelIdentityKey(String channelName, String markerId) {
        String marker = emptyToNull(markerId);
        if (marker != null) return "marker:" + marker;
        return "channel:" + requireText("channelName", channelName);
    }

    private static Map<String, ChannelSetting> immutableChannelSettings(
            Map<String, ChannelSetting> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        List<String> keys = new ArrayList<String>(source.keySet());
        Collections.sort(keys);
        Map<String, ChannelSetting> out = new LinkedHashMap<String, ChannelSetting>();
        for (String key : keys) {
            ChannelSetting value = source.get(key);
            if (value == null) throw new IllegalArgumentException(
                    "Channel setting '" + key + "' is required.");
            if (!key.equals(value.identityKey)) throw new IllegalArgumentException(
                    "Channel map key '" + key + "' does not match '" + value.identityKey + "'.");
            if (out.put(key, value) != null) throw new IllegalArgumentException(
                    "Duplicate channel identity key '" + key + "'.");
        }
        return Collections.unmodifiableMap(out);
    }

    private static void validateChannelRoles(Map<String, ChannelSetting> settings,
                                             boolean extractProcessLength) {
        int nuclear = 0;
        int overlapMarkers = 0;
        int targets = 0;
        for (ChannelSetting setting : settings.values()) {
            if (setting.nuclearMarker) nuclear++;
            if (setting.overlapMarker) overlapMarkers++;
            if (setting.overlapTarget) targets++;
            if (setting.overlapMarker && setting.overlapTarget) {
                throw new IllegalArgumentException(
                        "Overlap marker channel cannot also be an overlap target: "
                                + setting.channelName);
            }
        }
        if (nuclear > 1) throw new IllegalArgumentException(
                "At most one nuclear marker channel may be selected.");
        if (!settings.isEmpty() && extractProcessLength && nuclear != 1) {
            throw new IllegalArgumentException(
                    "Process-length presets require exactly one nuclear marker channel.");
        }
        if (overlapMarkers > 1) throw new IllegalArgumentException(
                "At most one overlap-count marker channel may be selected.");
        if (targets > 0 && overlapMarkers != 1) throw new IllegalArgumentException(
                "Overlap-count targets require exactly one marker channel.");
    }

    private static boolean isBuiltInColocPresetName(String name) {
        String value = name == null ? "" : name.trim();
        return value.equals("Count + Coloc Loose")
                || value.equals("Count + Coloc Standard")
                || value.equals("Count + Coloc Strict")
                || isBuiltInFullWorkflowPresetName(value);
    }

    private static boolean isBuiltInFullWorkflowPresetName(String name) {
        return "Full workflow".equals(name == null ? "" : name.trim());
    }

    private static void rejectKeys(Map<String, Object> root, String... keys) throws IOException {
        for (String key : keys) {
            if (root.containsKey(key)) {
                throw new IOException("schemaVersion 2 must not contain legacy field '" + key + "'.");
            }
        }
    }

    private static String requiredText(Map<String, Object> root, String key) throws IOException {
        if (!root.containsKey(key) || !(root.get(key) instanceof String)
                || ((String) root.get(key)).trim().isEmpty()) {
            throw new IOException(key + " must be a non-empty string.");
        }
        return ((String) root.get(key)).trim();
    }

    private static String optionalString(Map<String, Object> root, String key, String fallback)
            throws IOException {
        if (!root.containsKey(key) || root.get(key) == null) return fallback;
        if (!(root.get(key) instanceof String)) throw new IOException(key + " must be a string.");
        String value = ((String) root.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String optionalNullableString(Map<String, Object> root, String key)
            throws IOException {
        if (!root.containsKey(key) || root.get(key) == null) return null;
        if (!(root.get(key) instanceof String)) throw new IOException(key + " must be a string.");
        return emptyToNull((String) root.get(key));
    }

    private static boolean requiredBoolean(Map<String, Object> root, String key) throws IOException {
        if (!(root.get(key) instanceof Boolean)) throw new IOException(key + " must be a boolean.");
        return ((Boolean) root.get(key)).booleanValue();
    }

    private static boolean optionalBoolean(Map<String, Object> root, String key, boolean fallback)
            throws IOException {
        if (!root.containsKey(key) || root.get(key) == null) return fallback;
        return requiredBoolean(root, key);
    }

    private static int requiredInteger(String key, Object value) throws IOException {
        if (!(value instanceof Number)) throw new IOException(key + " must be an integer.");
        double numeric = ((Number) value).doubleValue();
        if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)
                || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) {
            throw new IOException(key + " must be an integer.");
        }
        return (int) numeric;
    }

    private static int requiredIntegerAtLeast(Map<String, Object> root, String key, int min)
            throws IOException {
        if (!root.containsKey(key)) throw new IOException(key + " is required.");
        int value = requiredInteger(key, root.get(key));
        if (value < min) throw new IOException(key + " must be at least " + min + ".");
        return value;
    }

    private static int optionalInteger(Map<String, Object> root, String key, int fallback, int min)
            throws IOException {
        if (!root.containsKey(key) || root.get(key) == null) return fallback;
        int value = requiredInteger(key, root.get(key));
        if (value < min) throw new IOException(key + " must be at least " + min + ".");
        return value;
    }

    private static double requiredNumber(String key, Object value) throws IOException {
        if (!(value instanceof Number)) throw new IOException(key + " must be a number.");
        double out = ((Number) value).doubleValue();
        if (!Double.isFinite(out)) throw new IOException(key + " must be finite.");
        return out;
    }

    private static double requiredPercent(Map<String, Object> root, String key) throws IOException {
        if (!root.containsKey(key)) throw new IOException(key + " is required.");
        return percent(key, requiredNumber(key, root.get(key)));
    }

    private static double optionalPercent(Map<String, Object> root, String key, double fallback)
            throws IOException {
        if (!root.containsKey(key) || root.get(key) == null) return fallback;
        return percent(key, requiredNumber(key, root.get(key)));
    }

    private static double requiredFiniteAtLeast(Map<String, Object> root, String key, double min)
            throws IOException {
        if (!root.containsKey(key)) throw new IOException(key + " is required.");
        double value = requiredNumber(key, root.get(key));
        if (value < min) throw new IOException(key + " must be at least " + min + ".");
        return value;
    }

    private static double optionalFiniteAtLeast(Map<String, Object> root, String key,
                                                 double fallback, double min) throws IOException {
        if (!root.containsKey(key) || root.get(key) == null) return fallback;
        double value = requiredNumber(key, root.get(key));
        if (value < min) throw new IOException(key + " must be at least " + min + ".");
        return value;
    }

    private static List<String> optionalStrings(Map<String, Object> root, String key)
            throws IOException {
        if (!root.containsKey(key) || root.get(key) == null) return Collections.emptyList();
        if (!(root.get(key) instanceof List)) throw new IOException(key + " must be an array.");
        List<String> out = new ArrayList<String>();
        for (Object item : (List<?>) root.get(key)) {
            if (!(item instanceof String) || ((String) item).trim().isEmpty()) {
                throw new IOException(key + " entries must be non-empty strings.");
            }
            out.add(((String) item).trim());
        }
        return out;
    }

    private static String normalizeOipRegion(String value) {
        String normalized = normalizeToken(value);
        if ("objectvoxels".equals(normalized) || "objectonly".equals(normalized)
                || "onlyobject".equals(normalized)) return OipConfig.Region.OBJECT_VOXELS.name();
        if ("wholebox".equals(normalized) || "box".equals(normalized)
                || "boundingbox".equals(normalized)) return OipConfig.Region.WHOLE_BOX.name();
        throw new IllegalArgumentException("Unsupported oipRegion '" + value + "'.");
    }

    private static String normalizeOipIntensityNorm(String value) {
        String normalized = normalizeToken(value);
        if ("dividebymean".equals(normalized) || "mean".equals(normalized)) {
            return OipConfig.IntensityNorm.DIVIDE_BY_MEAN.name();
        }
        if ("zscore".equals(normalized) || "z".equals(normalized)) {
            return OipConfig.IntensityNorm.ZSCORE.name();
        }
        if ("perobjectminmax".equals(normalized) || "minmax".equals(normalized)) {
            return OipConfig.IntensityNorm.PER_OBJECT_MINMAX.name();
        }
        throw new IllegalArgumentException("Unsupported oipIntensityNorm '" + value + "'.");
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
    }

    private static String requireText(String label, String value) {
        String trimmed = emptyToNull(value);
        if (trimmed == null) throw new IllegalArgumentException(label + " is required.");
        return trimmed;
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int requireAtLeast(String label, int value, int min) {
        if (value < min) throw new IllegalArgumentException(label + " must be at least " + min + ".");
        return value;
    }

    private static double requireFiniteAtLeast(String label, double value, double min) {
        if (!Double.isFinite(value) || value < min) {
            throw new IllegalArgumentException(label + " must be finite and at least " + min + ".");
        }
        return value;
    }

    private static double requirePercent(String label, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 100.0) {
            throw new IllegalArgumentException(label + " must be a finite percentage from 0 to 100.");
        }
        return value;
    }

    private static double percent(String label, double value) throws IOException {
        if (value < 0.0 || value > 100.0) {
            throw new IOException(label + " must be from 0 to 100.");
        }
        return value;
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Marker hints must be non-empty strings.");
            }
            out.add(value.trim());
        }
        return Collections.unmodifiableList(out);
    }

    /** Complete settings for exactly one stable channel identity. */
    public static final class ChannelSetting {
        private final String identityKey;
        private final String channelName;
        private final String markerId;
        private final double colocThresholdPercent;
        private final double bbColocThresholdPercent;
        private final boolean processChannel;
        private final boolean nuclearMarker;
        private final boolean overlapMarker;
        private final boolean overlapTarget;

        public ChannelSetting(String channelName,
                              String markerId,
                              double colocThresholdPercent,
                              double bbColocThresholdPercent,
                              boolean processChannel,
                              boolean nuclearMarker,
                              boolean overlapMarker,
                              boolean overlapTarget) {
            this.channelName = requireText("channelName", channelName);
            this.markerId = emptyToNull(markerId) == null ? "" : markerId.trim();
            this.identityKey = channelIdentityKey(this.channelName, this.markerId);
            this.colocThresholdPercent = requirePercent(
                    "colocThresholdPercent for " + this.channelName, colocThresholdPercent);
            this.bbColocThresholdPercent = requirePercent(
                    "bbColocThresholdPercent for " + this.channelName, bbColocThresholdPercent);
            this.processChannel = processChannel;
            this.nuclearMarker = nuclearMarker;
            this.overlapMarker = overlapMarker;
            this.overlapTarget = overlapTarget;
            if (overlapMarker && overlapTarget) {
                throw new IllegalArgumentException(
                        "Overlap marker cannot also be a target: " + this.channelName);
            }
        }

        public String getIdentityKey() { return identityKey; }
        public String getChannelName() { return channelName; }
        public String getMarkerId() { return markerId; }
        public double getColocThresholdPercent() { return colocThresholdPercent; }
        public double getBBColocThresholdPercent() { return bbColocThresholdPercent; }
        public boolean isProcessChannel() { return processChannel; }
        public boolean isNuclearMarker() { return nuclearMarker; }
        public boolean isOverlapMarker() { return overlapMarker; }
        public boolean isOverlapTarget() { return overlapTarget; }

        private Map<String, Object> toJsonObject() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("channelName", channelName);
            out.put("markerId", markerId);
            out.put("colocThresholdPercent", Double.valueOf(colocThresholdPercent));
            out.put("bbColocThresholdPercent", Double.valueOf(bbColocThresholdPercent));
            out.put("processChannel", Boolean.valueOf(processChannel));
            out.put("nuclearMarker", Boolean.valueOf(nuclearMarker));
            out.put("overlapMarker", Boolean.valueOf(overlapMarker));
            out.put("overlapTarget", Boolean.valueOf(overlapTarget));
            return out;
        }

        private static ChannelSetting fromJson(String identityKey, Object value) throws IOException {
            if (!(value instanceof Map)) {
                throw new IOException("Channel setting '" + identityKey + "' must be an object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            try {
                ChannelSetting setting = new ChannelSetting(
                        requiredText(map, "channelName"),
                        optionalString(map, "markerId", ""),
                        requiredPercent(map, "colocThresholdPercent"),
                        requiredPercent(map, "bbColocThresholdPercent"),
                        requiredBoolean(map, "processChannel"),
                        requiredBoolean(map, "nuclearMarker"),
                        requiredBoolean(map, "overlapMarker"),
                        requiredBoolean(map, "overlapTarget"));
                if (!identityKey.equals(setting.identityKey)) {
                    throw new IOException("Channel setting key '" + identityKey
                            + "' does not match stored identity '" + setting.identityKey + "'.");
                }
                return setting;
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid channel setting '" + identityKey + "': "
                        + e.getMessage(), e);
            }
        }
    }

    private static final class ChannelDefaults {
        final double colocThresholdPercent;
        final double bbColocThresholdPercent;
        final List<String> processMarkerHints;
        final List<String> nuclearMarkerHints;

        ChannelDefaults(double colocThresholdPercent,
                        double bbColocThresholdPercent,
                        List<String> processMarkerHints,
                        List<String> nuclearMarkerHints) {
            this.colocThresholdPercent = requirePercent(
                    "channelDefaults.colocThresholdPercent", colocThresholdPercent);
            this.bbColocThresholdPercent = requirePercent(
                    "channelDefaults.bbColocThresholdPercent", bbColocThresholdPercent);
            this.processMarkerHints = immutableStrings(processMarkerHints);
            this.nuclearMarkerHints = immutableStrings(nuclearMarkerHints);
        }

        Map<String, Object> toJsonObject() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("colocThresholdPercent", Double.valueOf(colocThresholdPercent));
            out.put("bbColocThresholdPercent", Double.valueOf(bbColocThresholdPercent));
            out.put("processMarkerHints", new ArrayList<String>(processMarkerHints));
            out.put("nuclearMarkerHints", new ArrayList<String>(nuclearMarkerHints));
            return out;
        }

        static ChannelDefaults fromJson(Object value) throws IOException {
            if (!(value instanceof Map)) throw new IOException("channelDefaults must be an object.");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            try {
                return new ChannelDefaults(
                        requiredPercent(map, "colocThresholdPercent"),
                        requiredPercent(map, "bbColocThresholdPercent"),
                        optionalStrings(map, "processMarkerHints"),
                        optionalStrings(map, "nuclearMarkerHints"));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid channelDefaults: " + e.getMessage(), e);
            }
        }
    }
}
