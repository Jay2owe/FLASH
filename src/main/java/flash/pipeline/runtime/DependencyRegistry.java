package flash.pipeline.runtime;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.cellpose.CellposeRuntime.Status;
import ij.Menus;
import ij.Prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for runtime dependency metadata, probes, and jar manifests.
 */
public final class DependencyRegistry {

    /** Pip spec for the Cellpose Python package this build expects.
     *  Upgrade procedure: bump here; verify via /Help → Cellpose Setup
     *  that probe still detects the new version; update RELEASE_NOTES. */
    public static final String SUPPORTED_CELLPOSE_VERSION = "3.1.1.2";

    public static final long PLUGIN_JAR_INTEGRITY_BYTES = 3449815L;
    public static final long IMAGEJ_RUNTIME_BYTES = 2589983L;
    public static final long BIO_FORMATS_RUNTIME_BYTES = 250459L;
    public static final long OBJECTS_COUNTER_3D_BYTES = 22913L;
    public static final long MCIB3D_CORE_BYTES = 1572864L;
    public static final long NUCLEUS_COUNTER_BYTES = 100488L;
    public static final long STARDIST_RUNTIME_BYTES = 22775071L;
    public static final long TENSORFLOW_NATIVE_RUNTIME_BYTES = 143819615L;
    public static final long APACHE_POI_RUNTIME_BYTES = 13820232L;
    public static final long CELLPOSE_CPU_RUNTIME_BYTES = 524288000L;
    public static final long CELLPOSE_GPU_RUNTIME_BYTES = 2684354560L;
    public static final long JTS_CORE_BYTES = 1103721L;

    private static final List<String> STARDIST_IGNORE_PREFIXES =
            Collections.unmodifiableList(Arrays.asList("protobuf-java-util-", "proto-google-"));

    private static final List<DependencySpec.JarRequirement> BIO_FORMATS_RUNTIME_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "Bio-Formats plugins",
                            "bio-formats_plugins-8.1.1.jar",
                            "bio-formats_plugins-",
                            "plugins",
                            "https://sites.imagej.net/Java-8/plugins/bio-formats_plugins-8.1.1.jar-20250305153134",
                            "b25b203d868deba8dd6c63620bf421def3db0d88",
                            true)
            ));

    private static final List<DependencySpec.JarRequirement> OBJECTS_COUNTER_3D_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "3D Objects Counter",
                            "3D_Objects_Counter-2.0.1.jar",
                            "3D_Objects_Counter-",
                            "plugins",
                            "https://sites.imagej.net/Java-8/plugins/3D_Objects_Counter-2.0.1.jar-20170530201750",
                            "8ee3f3828526c3358695f9160e18c124e077c02c",
                            true)
            ));

    private static final List<DependencySpec.JarRequirement> NUCLEUS_COUNTER_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "Fiji Cookbook Nucleus Counter",
                            "cookbook_.jar",
                            "cookbook_",
                            "plugins",
                            "https://sites.imagej.net/Cookbook/plugins/cookbook_.jar-20191007151226",
                            "c7c4130b70d35f734bf627f2272dce07e4d3d7e9",
                            true)
            ));

    private static final List<DependencySpec.JarRequirement> STARDIST_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "TrackMate",
                            "TrackMate-7.14.0.jar",
                            "TrackMate-",
                            "jars",
                            "https://maven.scijava.org/content/groups/public/sc/fiji/TrackMate/7.14.0/TrackMate-7.14.0.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "TrackMate-StarDist",
                            "TrackMate-StarDist-1.2.1.jar",
                            "TrackMate-StarDist-",
                            "jars",
                            "https://maven.scijava.org/content/groups/public/sc/fiji/TrackMate-StarDist/1.2.1/TrackMate-StarDist-1.2.1.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "StarDist",
                            "StarDist_-0.3.0.jar",
                            "StarDist_-",
                            "plugins",
                            "https://maven.scijava.org/content/groups/public/de/csbdresden/StarDist_/0.3.0-scijava/StarDist_-0.3.0-scijava.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "CSBDeep",
                            "csbdeep-0.6.0.jar",
                            "csbdeep-",
                            "jars",
                            "https://maven.scijava.org/content/groups/public/de/csbdresden/csbdeep/0.6.0/csbdeep-0.6.0.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "imagej-tensorflow",
                            "imagej-tensorflow-1.1.5.jar",
                            "imagej-tensorflow-",
                            "jars",
                            "https://maven.scijava.org/content/groups/public/net/imagej/imagej-tensorflow/1.1.5/imagej-tensorflow-1.1.5.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "TensorFlow proto",
                            "proto-1.15.0.jar",
                            "proto-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/tensorflow/proto/1.15.0/proto-1.15.0.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "protobuf-java",
                            "protobuf-java-3.5.1.jar",
                            "protobuf-java-",
                            "jars",
                            "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.5.1/protobuf-java-3.5.1.jar",
                            false)
            ));

    private static final List<DependencySpec.JarRequirement> TENSORFLOW_NATIVE_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "TensorFlow core",
                            "tensorflow-1.15.0.jar",
                            "tensorflow-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/tensorflow/tensorflow/1.15.0/tensorflow-1.15.0.jar",
                            "e6a186dca82681e1e28615167e38859b99f82235",
                            false),
                    new DependencySpec.JarRequirement(
                            "TensorFlow native library",
                            "libtensorflow-1.15.0.jar",
                            "libtensorflow-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/tensorflow/libtensorflow/1.15.0/libtensorflow-1.15.0.jar",
                            "578c89321585d40dbcf7d038dd6c09f2ec744002",
                            false),
                    new DependencySpec.JarRequirement(
                            "TensorFlow JNI bridge",
                            "libtensorflow_jni-1.15.0.jar",
                            "libtensorflow_jni-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/tensorflow/libtensorflow_jni/1.15.0/libtensorflow_jni-1.15.0.jar",
                            "e749c7ce289ad236914657a11b3c198f35ae5f41",
                            false)
            ));

    private static final List<DependencySpec.JarRequirement> EXCEL_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "Apache POI core",
                            "poi-3.17.jar",
                            "poi-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/apache/poi/poi/3.17/poi-3.17.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "Apache POI OOXML",
                            "poi-ooxml-3.17.jar",
                            "poi-ooxml-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/apache/poi/poi-ooxml/3.17/poi-ooxml-3.17.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "Apache POI OOXML schemas",
                            "poi-ooxml-schemas-3.17.jar",
                            "poi-ooxml-schemas-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/apache/poi/poi-ooxml-schemas/3.17/poi-ooxml-schemas-3.17.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "XMLBeans",
                            "xmlbeans-3.1.0.jar",
                            "xmlbeans-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/apache/xmlbeans/xmlbeans/3.1.0/xmlbeans-3.1.0.jar",
                            true),
                    new DependencySpec.JarRequirement(
                            "curvesapi",
                            "curvesapi-1.04.jar",
                            "curvesapi-",
                            "jars",
                            "https://repo1.maven.org/maven2/com/github/virtuald/curvesapi/1.04/curvesapi-1.04.jar",
                            false),
                    new DependencySpec.JarRequirement(
                            "commons-collections4",
                            "commons-collections4-4.1.jar",
                            "commons-collections4-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/apache/commons/commons-collections4/4.1/commons-collections4-4.1.jar",
                            true),
                    new DependencySpec.JarRequirement(
                            "commons-codec",
                            "commons-codec-1.10.jar",
                            "commons-codec-",
                            "jars",
                            "https://repo1.maven.org/maven2/commons-codec/commons-codec/1.10/commons-codec-1.10.jar",
                            true)
            ));

    private static final List<DependencySpec.JarRequirement> JTS_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "JTS core",
                            "jts-core-1.19.0.jar",
                            "jts-core-",
                            "jars",
                            "https://repo1.maven.org/maven2/org/locationtech/jts/jts-core/1.19.0/jts-core-1.19.0.jar",
                            "3ff3baa0074445384f9e0068df81fbd0a168395a",
                            false)
            ));

    private static final Map<DependencyId, DependencySpec> SPECS = buildSpecs();

    private DependencyRegistry() {}

    public static final class ProbeContext {
        private final ClassLoader classLoader;
        private final File fijiDir;

        private ProbeContext(ClassLoader classLoader, File fijiDir) {
            this.classLoader = classLoader;
            this.fijiDir = fijiDir;
        }

        public ClassLoader getClassLoader() {
            return classLoader;
        }

        public File getFijiDir() {
            return fijiDir;
        }
    }

    public static List<DependencySpec> all() {
        return Collections.unmodifiableList(new ArrayList<DependencySpec>(SPECS.values()));
    }

    public static DependencySpec get(DependencyId id) {
        return SPECS.get(id);
    }

    public static Map<DependencyId, DependencySpec> lookup() {
        return SPECS;
    }

    public static EnumMap<DependencyId, DependencyStatus> snapshotStatuses(List<DependencySpec> specs) {
        ProbeContext context = new ProbeContext(DependencyRegistry.class.getClassLoader(), resolveFijiDir());
        EnumMap<DependencyId, DependencyStatus> snapshot = new EnumMap<DependencyId, DependencyStatus>(DependencyId.class);
        if (specs == null) {
            return snapshot;
        }
        for (DependencySpec spec : specs) {
            snapshot.put(spec.getId(), spec.probe(context));
        }
        return snapshot;
    }

    public static File resolveFijiDir() {
        String fijiDir = System.getProperty("fiji.dir");
        if (fijiDir != null && new File(fijiDir).isDirectory()) {
            return new File(fijiDir);
        }

        String ijDir = System.getProperty("ij.dir");
        if (ijDir != null && new File(ijDir).isDirectory()) {
            return new File(ijDir);
        }

        String prefsDir = Prefs.getHomeDir();
        if (prefsDir != null && new File(prefsDir).isDirectory()) {
            return new File(prefsDir);
        }

        return null;
    }

    public static String formatApproxSize(long bytes) {
        if (bytes < 0L) {
            return "";
        }
        if (bytes == 0L) {
            return "(~0 B)";
        }
        if (bytes >= (1L << 30)) {
            return "(~" + formatDecimal(bytes / (double) (1L << 30)) + " GB)";
        }
        if (bytes >= (1L << 20)) {
            return "(~" + formatDecimal(bytes / (double) (1L << 20)) + " MB)";
        }
        long kb = Math.max(1L, Math.round(bytes / 1024.0));
        return "(~" + kb + " KB)";
    }

    public static List<String> checkJarRequirements(File fijiDir,
                                                    List<DependencySpec.JarRequirement> requirements,
                                                    List<String> ignorePrefixes) {
        List<String> issues = new ArrayList<String>();
        if (fijiDir == null) {
            issues.add("Could not determine the Fiji.app directory.");
            return issues;
        }
        if (requirements == null) {
            return issues;
        }

        for (DependencySpec.JarRequirement requirement : requirements) {
            File dir = new File(fijiDir, requirement.getFolder());
            File expected = new File(dir, requirement.getExpectedFile());
            if (expected.exists()) {
                continue;
            }

            File compatible = findMatchingJar(dir, requirement.getMatchPrefix(), ignorePrefixes);
            if (requirement.isAcceptAnyExisting() && compatible != null) {
                continue;
            }

            if (compatible != null) {
                issues.add(requirement.getLabel() + ": found " + compatible.getName()
                        + ", need " + requirement.getExpectedFile());
            } else {
                issues.add(requirement.getLabel() + ": MISSING (need " + requirement.getExpectedFile() + ")");
            }
        }

        return issues;
    }

    public static List<String> repairJarRequirements(File fijiDir,
                                                     List<DependencySpec.JarRequirement> requirements,
                                                     List<String> ignorePrefixes) {
        List<String> actions = new ArrayList<String>();
        if (fijiDir == null) {
            actions.add("FAILED: Could not determine the Fiji.app directory.");
            return actions;
        }
        if (requirements == null) {
            return actions;
        }

        String dateSuffix = new SimpleDateFormat("yyyyMMdd").format(new Date());
        for (DependencySpec.JarRequirement requirement : requirements) {
            File dir = new File(fijiDir, requirement.getFolder());
            if (!dir.exists() && !dir.mkdirs()) {
                actions.add("FAILED to prepare " + requirement.getFolder() + "/: could not create "
                        + dir.getAbsolutePath());
                continue;
            }
            if (!dir.isDirectory()) {
                actions.add("FAILED to prepare " + requirement.getFolder() + "/: not a directory: "
                        + dir.getAbsolutePath());
                continue;
            }
            File expected = new File(dir, requirement.getExpectedFile());
            if (expected.exists()) {
                continue;
            }

            File compatible = findMatchingJar(dir, requirement.getMatchPrefix(), ignorePrefixes);
            if (requirement.isAcceptAnyExisting() && compatible != null) {
                actions.add("Using existing: " + compatible.getName());
                continue;
            }

            disableWrongVersions(dir, requirement, dateSuffix, actions, null, ignorePrefixes);
            String otherFolder = "jars".equals(requirement.getFolder()) ? "plugins" : "jars";
            disableWrongVersions(new File(fijiDir, otherFolder), requirement, dateSuffix, actions, otherFolder, ignorePrefixes);

            if (requirement.getDownloadUrl() == null || requirement.getDownloadUrl().trim().isEmpty()) {
                actions.add("FAILED to download " + requirement.getExpectedFile() + ": no download URL is defined.");
                continue;
            }

            try {
                downloadFile(requirement.getDownloadUrl(), expected, requirement.getExpectedSha1());
                actions.add("Downloaded: " + requirement.getExpectedFile()
                        + " (" + (expected.length() / 1024) + " KB)");
            } catch (Exception e) {
                actions.add("FAILED to download " + requirement.getExpectedFile() + ": " + e.getMessage());
            }
        }

        return actions;
    }

    private static Map<DependencyId, DependencySpec> buildSpecs() {
        LinkedHashMap<DependencyId, DependencySpec> specs = new LinkedHashMap<DependencyId, DependencySpec>();

        specs.put(DependencyId.PLUGIN_JAR_INTEGRITY, DependencySpec.builder(
                        DependencyId.PLUGIN_JAR_INTEGRITY,
                        "FLASH plugin jar integrity")
                .description("Exactly one live FLASH jar should be present in Fiji's plugins folder.")
                .affectedFeatures("Whole plugin startup and every analysis module")
                .criticality(DependencySpec.Criticality.INTERNAL_INTEGRITY)
                .detectionStrategyLabel("Composite plugin-jar scan")
                .probe(pluginJarProbe())
                .fixerStrategy(DependencySpec.FixerStrategy.MANUAL_PLUGIN_REINSTALL)
                .approxDownloadSizeBytes(PLUGIN_JAR_INTEGRITY_BYTES)
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason("Manual repair only. Close Fiji, remove stale IHF plugin jars, copy one fresh plugin jar, and restart.")
                .visibleInDependenciesDialog(false)
                .build());

        specs.put(DependencyId.IMAGEJ_RUNTIME, DependencySpec.builder(
                        DependencyId.IMAGEJ_RUNTIME,
                        "ImageJ runtime")
                .description("Base ImageJ / Fiji classes required for the plugin shell to run at all.")
                .affectedFeatures("Whole plugin startup and every analysis module")
                .criticality(DependencySpec.Criticality.STARTUP_CRITICAL)
                .detectionStrategyLabel("Class probe")
                .probe(classProbe("ij.IJ", "ij.plugin.PlugIn"))
                .fixerStrategy(DependencySpec.FixerStrategy.NONE)
                .approxDownloadSizeBytes(IMAGEJ_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason("Managed by Fiji itself, not by the plugin.")
                .visibleInDependenciesDialog(false)
                .build());

        specs.put(DependencyId.BIO_FORMATS_RUNTIME, DependencySpec.builder(
                        DependencyId.BIO_FORMATS_RUNTIME,
                        "Bio-Formats / OME / SCIFIO runtime")
                .description("Needed for .lif opening, metadata reads, and the Bio-Formats-backed import/export path.")
                .affectedFeatures(".lif opening", "metadata import", "OME-TIFF writing")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe")
                .probe(classProbe(
                        "loci.plugins.BF",
                        "loci.plugins.in.ImporterOptions",
                        "loci.formats.ImageReader",
                        "ome.units.UNITS"))
                .fixerStrategy(DependencySpec.FixerStrategy.FIJI_UPDATER)
                .approxDownloadSizeBytes(BIO_FORMATS_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Auto-Fix Bio-Formats%s")
                .presentButtonLabel("Verify Bio-Formats Runtime")
                .visibleInDependenciesDialog(true)
                .jarRequirements(BIO_FORMATS_RUNTIME_JARS)
                .build());

        specs.put(DependencyId.OBJECTS_COUNTER_3D, DependencySpec.builder(
                        DependencyId.OBJECTS_COUNTER_3D,
                        "3D Objects Counter")
                .description("Legacy Fiji counter used for classical 3D object counting and size previews.")
                .affectedFeatures("classical 3D object counting", "Set Up Configuration 3D preview", "process length path")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("ImageJ command probe")
                .probe(commandProbe("3D Objects Counter"))
                .fixerStrategy(DependencySpec.FixerStrategy.FIJI_UPDATER)
                .approxDownloadSizeBytes(OBJECTS_COUNTER_3D_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Auto-Fix 3D Objects Counter%s")
                .presentButtonLabel("Verify 3D Objects Counter")
                .visibleInDependenciesDialog(true)
                .jarRequirements(OBJECTS_COUNTER_3D_JARS)
                .build());

        specs.put(DependencyId.MCIB3D_CORE, DependencySpec.builder(
                        DependencyId.MCIB3D_CORE,
                        "mcib3d-core")
                .description("Native 3D ImageJ Suite runtime for thread-safe 3D morphology and label-image measurements.")
                .affectedFeatures("native 3D morphology", "label-image measurements", "spatial morphometry")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Class probe")
                .probe(classProbe("mcib3d.image3d.ImageLabeller", "mcib3d.geom2.Objects3DIntPopulation"))
                .fixerStrategy(DependencySpec.FixerStrategy.FIJI_UPDATER)
                .approxDownloadSizeBytes(MCIB3D_CORE_BYTES)
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason("Install the 3D ImageJ Suite runtime through Fiji's updater and restart.")
                .visibleInDependenciesDialog(true)
                .build());

        specs.put(DependencyId.NUCLEUS_COUNTER, DependencySpec.builder(
                        DependencyId.NUCLEUS_COUNTER,
                        "Nucleus Counter")
                .description("External Fiji plugin used only by the Nuclear Counter module.")
                .affectedFeatures("Nuclear Counter module")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("ImageJ command probe")
                .probe(commandProbe("Nucleus Counter"))
                .fixerStrategy(DependencySpec.FixerStrategy.FIJI_UPDATER)
                .approxDownloadSizeBytes(NUCLEUS_COUNTER_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Auto-Fix Nucleus Counter%s")
                .presentButtonLabel("Verify Nucleus Counter")
                .visibleInDependenciesDialog(true)
                .jarRequirements(NUCLEUS_COUNTER_JARS)
                .build());

        specs.put(DependencyId.STARDIST_RUNTIME, DependencySpec.builder(
                        DependencyId.STARDIST_RUNTIME,
                        "StarDist / TrackMate Java stack")
                .description("Java-side TrackMate, StarDist, CSBDeep, and TensorFlow bridge jars checked by the existing RuntimeChecker.")
                .affectedFeatures("StarDist segmentation", "StarDist previews")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Jar presence / version probe")
                .probe(jarProbe(STARDIST_JARS, STARDIST_IGNORE_PREFIXES))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(STARDIST_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Auto-Fix StarDist%s")
                .presentButtonLabel("Verify StarDist Runtime")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("StarDist")
                .jarRequirements(STARDIST_JARS)
                .jarIgnorePrefixes(STARDIST_IGNORE_PREFIXES)
                .build());

        specs.put(DependencyId.TENSORFLOW_NATIVE_RUNTIME, DependencySpec.builder(
                        DependencyId.TENSORFLOW_NATIVE_RUNTIME,
                        "TensorFlow native runtime")
                .description("Large TensorFlow jars observed in working Fiji installs and still required for StarDist execution.")
                .affectedFeatures("StarDist segmentation", "StarDist previews")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Jar presence / version probe")
                .probe(jarProbe(TENSORFLOW_NATIVE_JARS, Collections.<String>emptyList()))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(TENSORFLOW_NATIVE_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Auto-Fix TensorFlow Native%s")
                .presentButtonLabel("Verify TensorFlow Native Runtime")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("StarDist")
                .jarRequirements(TENSORFLOW_NATIVE_JARS)
                .build());

        specs.put(DependencyId.APACHE_POI_RUNTIME, DependencySpec.builder(
                        DependencyId.APACHE_POI_RUNTIME,
                        "Apache POI runtime")
                .description("Apache POI jars used only by Excel Summary Export.")
                .affectedFeatures("Excel Summary Export")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe + jar presence / version probe")
                .probe(composite(
                        classProbe(
                                "org.apache.poi.ss.usermodel.Workbook",
                                "org.apache.poi.xssf.usermodel.XSSFWorkbook",
                                "org.apache.xmlbeans.XmlObject",
                                "org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbook"),
                        jarProbe(EXCEL_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(APACHE_POI_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Auto-Fix Excel%s")
                .presentButtonLabel("Verify Excel Runtime")
                .visibleInDependenciesDialog(true)
                .jarRequirements(EXCEL_JARS)
                .build());

        specs.put(DependencyId.CELLPOSE_RUNTIME, DependencySpec.builder(
                        DependencyId.CELLPOSE_RUNTIME,
                        "Cellpose runtime")
                .description("Managed or existing Python environment with the pinned Cellpose version.")
                .affectedFeatures("Cellpose segmentation")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Python runtime probe")
                .probe(cellposeProbe())
                .fixerStrategy(DependencySpec.FixerStrategy.PYTHON_SETUP)
                .approxDownloadSizeBytes(CELLPOSE_CPU_RUNTIME_BYTES)
                .restartRequired(false)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install / Fix Cellpose%s")
                .presentButtonLabel("Verify Cellpose Runtime")
                .visibleInDependenciesDialog(true)
                .installOptions(Arrays.asList(
                        new DependencySpec.InstallOption(
                                "cellpose_cpu",
                                "Install Cellpose CPU%s",
                                CELLPOSE_CPU_RUNTIME_BYTES),
                        new DependencySpec.InstallOption(
                                "cellpose_gpu",
                                "Install Cellpose GPU%s",
                                CELLPOSE_GPU_RUNTIME_BYTES)))
                .build());

        specs.put(DependencyId.JTS_CORE, DependencySpec.builder(
                        DependencyId.JTS_CORE,
                        "JTS / Voronoi geometry")
                .description("Pure Java geometry runtime used by Voronoi tessellation and related spatial outputs.")
                .affectedFeatures("Voronoi tessellation", "spatial morphology outputs")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Class probe + jar presence / version probe")
                .probe(composite(
                        classProbe(
                                "org.locationtech.jts.geom.GeometryFactory",
                                "org.locationtech.jts.triangulate.VoronoiDiagramBuilder"),
                        jarProbe(JTS_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(JTS_CORE_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Auto-Fix JTS%s")
                .presentButtonLabel("Verify JTS Runtime")
                .visibleInDependenciesDialog(true)
                .jarRequirements(JTS_JARS)
                .build());

        return Collections.unmodifiableMap(specs);
    }

    private static DependencySpec.Probe classProbe(final String... classNames) {
        return new DependencySpec.Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                if (classNames == null || classNames.length == 0) {
                    return DependencyStatus.error("No runtime classes were configured for this dependency.");
                }
                ClassLoader loader = context.getClassLoader();
                for (String className : classNames) {
                    try {
                        Class.forName(className, false, loader);
                    } catch (ClassNotFoundException e) {
                        return DependencyStatus.missing("Missing runtime class: " + className);
                    } catch (NoClassDefFoundError e) {
                        return DependencyStatus.missing("Missing runtime class: " + normalizeMissingClass(e));
                    } catch (LinkageError e) {
                        return DependencyStatus.error("Could not load " + className + ": "
                                + e.getClass().getSimpleName() + ": " + safeMessage(e));
                    }
                }
                return DependencyStatus.present("Required runtime classes are available.");
            }
        };
    }

    private static DependencySpec.Probe commandProbe(final String commandName) {
        return new DependencySpec.Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                Hashtable commands = Menus.getCommands();
                if (commands == null) {
                    return DependencyStatus.error("ImageJ command table is not available yet.");
                }
                if (commands.containsKey(commandName)) {
                    return DependencyStatus.present("ImageJ command available: " + commandName);
                }
                return DependencyStatus.missing("ImageJ command not found: " + commandName);
            }
        };
    }

    private static DependencySpec.Probe jarProbe(final List<DependencySpec.JarRequirement> requirements,
                                                 final List<String> ignorePrefixes) {
        return new DependencySpec.Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                if (context.getFijiDir() == null) {
                    return DependencyStatus.error("Could not determine the Fiji.app directory.");
                }
                List<String> issues = checkJarRequirements(context.getFijiDir(), requirements, ignorePrefixes);
                if (issues.isEmpty()) {
                    return DependencyStatus.present("All required Fiji jars are present.");
                }
                return DependencyStatus.missing(joinLines(issues));
            }
        };
    }

    private static DependencySpec.Probe composite(final DependencySpec.Probe... probes) {
        return new DependencySpec.Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                List<String> missing = new ArrayList<String>();
                List<String> errors = new ArrayList<String>();
                if (probes != null) {
                    for (DependencySpec.Probe probe : probes) {
                        if (probe == null) {
                            continue;
                        }
                        DependencyStatus status = probe.probe(context);
                        if (status == null || status.isPresent()) {
                            continue;
                        }
                        if (status.isError()) {
                            errors.add(status.getDetailMessage());
                        } else {
                            missing.add(status.getDetailMessage());
                        }
                    }
                }
                if (!errors.isEmpty()) {
                    return DependencyStatus.error(joinLines(errors));
                }
                if (!missing.isEmpty()) {
                    return DependencyStatus.missing(joinLines(missing));
                }
                return DependencyStatus.present("All checks passed.");
            }
        };
    }

    private static DependencySpec.Probe cellposeProbe() {
        return new DependencySpec.Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                Status status = CellposeRuntime.probeConfigured();
                if (status.ready) {
                    return DependencyStatus.present(status.summary());
                }
                if (status.message == null || status.message.trim().isEmpty()) {
                    return DependencyStatus.missing("Cellpose is not configured yet.");
                }
                return DependencyStatus.missing(status.message
                        + (status.details == null || status.details.trim().isEmpty()
                        ? ""
                        : "\n" + status.details.trim()));
            }
        };
    }

    private static DependencySpec.Probe pluginJarProbe() {
        return new DependencySpec.Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                File fijiDir = context.getFijiDir();
                if (fijiDir == null) {
                    return DependencyStatus.error("Could not determine the Fiji.app directory.");
                }
                File pluginsDir = new File(fijiDir, "plugins");
                File[] files = pluginsDir.listFiles();
                if (files == null) {
                    return DependencyStatus.error("Could not read Fiji plugins/ to verify the installed plugin jar.");
                }

                List<String> liveJars = new ArrayList<String>();
                List<String> staleJars = new ArrayList<String>();
                for (File file : files) {
                    if (file == null || !file.isFile()) {
                        continue;
                    }
                    String name = file.getName();
                    if (!name.endsWith(".jar")) {
                        continue;
                    }
                    if (name.startsWith("FLASH-") && !name.contains("-sources")
                            && !name.contains("-tests") && !name.startsWith("original-")) {
                        liveJars.add(name);
                    } else if (name.startsWith("original-FLASH-")
                            || name.contains("FLASH-") && (name.contains("-sources")
                            || name.contains("-tests") || name.contains("-shaded"))
                            || name.contains("IHF-Analysis-Pipeline-")) {
                        staleJars.add(name);
                    }
                }

                if (liveJars.isEmpty()) {
                    return DependencyStatus.missing("No live FLASH jar was found in Fiji plugins/.");
                }
                if (liveJars.size() > 1) {
                    return DependencyStatus.error("Multiple live FLASH plugin jars found: " + joinComma(liveJars));
                }
                if (!staleJars.isEmpty()) {
                    return DependencyStatus.error("Stale plugin jar copies detected beside the live jar: " + joinComma(staleJars));
                }
                return DependencyStatus.present("Detected live plugin jar: " + liveJars.get(0));
            }
        };
    }

    private static File findMatchingJar(File dir, String prefix, List<String> ignorePrefixes) {
        File[] candidates = dir.listFiles();
        if (candidates == null) {
            return null;
        }
        for (File file : candidates) {
            String name = file.getName();
            if (!file.isFile()) {
                continue;
            }
            if (!name.endsWith(".jar")) {
                continue;
            }
            if (shouldIgnore(name, ignorePrefixes)) {
                continue;
            }
            if (name.startsWith(prefix)) {
                return file;
            }
        }
        return null;
    }

    private static void disableWrongVersions(File dir,
                                             DependencySpec.JarRequirement requirement,
                                             String dateSuffix,
                                             List<String> actions,
                                             String folderLabel,
                                             List<String> ignorePrefixes) {
        File[] candidates = dir.listFiles();
        if (candidates == null) {
            return;
        }
        for (File file : candidates) {
            String name = file.getName();
            if (!file.isFile()) {
                continue;
            }
            if (!name.endsWith(".jar")) {
                continue;
            }
            if (shouldIgnore(name, ignorePrefixes)) {
                continue;
            }
            if (!name.startsWith(requirement.getMatchPrefix())) {
                continue;
            }
            if (name.equals(requirement.getExpectedFile())) {
                continue;
            }

            File disabled = new File(dir, name + ".disabled-" + dateSuffix);
            if (file.renameTo(disabled)) {
                actions.add(folderLabel == null
                        ? "Disabled: " + name
                        : "Disabled: " + name + " (from " + folderLabel + "/)");
            } else {
                actions.add("FAILED to disable: " + name + " (is Fiji running? Close it first.)");
            }
        }
    }

    private static boolean shouldIgnore(String fileName, List<String> ignorePrefixes) {
        if (ignorePrefixes == null || ignorePrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : ignorePrefixes) {
            if (fileName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void downloadFile(String urlStr, File dest, String expectedSha1) throws Exception {
        File temp = new File(dest.getParentFile(), dest.getName() + ".download");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            URL url = new URL(urlStr);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            in = connection.getInputStream();
            out = new FileOutputStream(temp);

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.close();
            out = null;

            if (temp.length() < 1000L) {
                temp.delete();
                throw new Exception("Download too small - likely an error page");
            }
            verifySha1(temp, expectedSha1);

            if (dest.exists()) {
                dest.delete();
            }
            if (!temp.renameTo(dest)) {
                throw new Exception("Could not move download to " + dest.getName());
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    private static void verifySha1(File file, String expectedSha1) throws Exception {
        if (expectedSha1 == null || expectedSha1.trim().isEmpty()) {
            return;
        }
        String actualSha1 = sha1(file);
        if (!expectedSha1.trim().equalsIgnoreCase(actualSha1)) {
            throw new Exception("SHA-1 mismatch for " + file.getName()
                    + " (expected " + expectedSha1.trim() + ", got " + actualSha1 + ")");
        }
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String normalizeMissingClass(NoClassDefFoundError error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "unknown";
        }
        String trimmed = message.trim();
        if (trimmed.startsWith("Could not initialize class ")) {
            trimmed = trimmed.substring("Could not initialize class ".length()).trim();
        }
        return trimmed.replace('/', '.');
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()) {
            return "no detail message";
        }
        return throwable.getMessage().trim();
    }

    private static String formatDecimal(double value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        DecimalFormat format = new DecimalFormat("0.#", symbols);
        return format.format(value);
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.trim());
        }
        return sb.toString();
    }

    private static String joinComma(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item.trim());
        }
        return sb.toString();
    }
}
