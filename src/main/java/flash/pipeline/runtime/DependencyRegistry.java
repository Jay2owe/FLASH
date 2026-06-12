package flash.pipeline.runtime;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.cellpose.CellposeRuntime.Status;
import ij.Menus;
import ij.Prefs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarFile;

/**
 * Single source of truth for runtime dependency metadata, probes, and jar manifests.
 */
public final class DependencyRegistry {

    interface JarFileOps {
        boolean rename(File source, File disabled);

        boolean scheduleDisable(File source, File disabled);
    }

    /** Pip spec for the Cellpose Python package this build expects.
     *  Upgrade procedure: bump here; verify via /Help → Cellpose Setup
     *  that probe still detects the new version; update RELEASE_NOTES. */
    public static final String SUPPORTED_CELLPOSE_VERSION = "3.1.1.2";

    public static final long PLUGIN_JAR_INTEGRITY_BYTES = 3449815L;
    public static final long IMAGEJ_RUNTIME_BYTES = 2589983L;
    public static final long BIO_FORMATS_RUNTIME_BYTES = 250459L;
    public static final long OBJECTS_COUNTER_3D_BYTES = 22913L;
    public static final long OBJECTS_COUNTER_3D_PLUS_BYTES = 194990L;
    public static final long MCIB3D_CORE_BYTES = 1572864L;
    public static final long STARDIST_RUNTIME_BYTES = 22775071L;
    public static final long TENSORFLOW_NATIVE_RUNTIME_BYTES = 143819615L;
    public static final long APACHE_POI_RUNTIME_BYTES = 13820232L;
    public static final long CELLPOSE_CPU_RUNTIME_BYTES = 524288000L;
    public static final long CELLPOSE_GPU_RUNTIME_BYTES = 2684354560L;
    public static final long EPFL_PSF_GENERATOR_RUNTIME_BYTES = 374403L;
    public static final long DECONV_CLIJ2_RUNTIME_BYTES = 8469845L;
    public static final long DECONVOLUTIONLAB2_RUNTIME_BYTES = 2718343L;
    public static final long ITERATIVE_DECONVOLVE_3D_RUNTIME_BYTES = 16573L;
    public static final long JTS_CORE_BYTES = 1103721L;
    public static final long COLOC2_RUNTIME_BYTES = 153303L;
    public static final long IMGLIB2_ALGORITHM_RUNTIME_BYTES = 1145386L;
    public static final long IMGLIB2_FFT_RUNTIME_BYTES = 20656L;
    public static final long JTRANSFORMS_RUNTIME_BYTES = 498954L;
    public static final long ORIENTATIONJ_RUNTIME_BYTES = 448691L;

    private static final List<String> STARDIST_IGNORE_PREFIXES =
            Collections.unmodifiableList(Arrays.asList("protobuf-java-util-", "proto-google-"));

    private static final Set<String> SCHEDULED_DISABLES =
            Collections.synchronizedSet(new HashSet<String>());
    private static final JarFileOps DEFAULT_JAR_FILE_OPS = new JarFileOps() {
        @Override
        public boolean rename(File source, File disabled) {
            return source.renameTo(disabled);
        }

        @Override
        public boolean scheduleDisable(File source, File disabled) {
            return scheduleWindowsDisableAfterExit(source, disabled);
        }
    };
    private static JarFileOps jarFileOps = DEFAULT_JAR_FILE_OPS;

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

    private static final List<DependencySpec.JarRequirement> EPFL_PSF_GENERATOR_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "EPFL PSF Generator",
                            "PSF_Generator.jar",
                            "PSF_Generator-",
                            "plugins",
                            "https://bigwww.epfl.ch/algorithms/psfgenerator/PSF_Generator.jar",
                            "14123eaac9ded5c007414e493bb0045f927b970d",
                            false)
            ));

    private static final List<DependencySpec.JarRequirement> DECONV_CLIJ2_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "clij2-fft",
                            "clij2-fft_-2.2.0.22.jar",
                            "clij2-fft_-",
                            "plugins",
                            "https://sites.imagej.net/clijx-deconvolution/plugins/clij2-fft_-2.2.0.22.jar-20251218161549",
                            "c3e072f6aad0fc6dacef2a883385f24c395b0303",
                            false),
                    new DependencySpec.JarRequirement(
                            "CLIJ2",
                            "clij2_-2.5.3.5.jar",
                            "clij2_-",
                            "plugins",
                            "https://sites.imagej.net/clij2/plugins/clij2_-2.5.3.5.jar-20240704214737",
                            "64a3742743d322e466cf6fed2917a82d9d5c1165",
                            true),
                    new DependencySpec.JarRequirement(
                            "CLIJ",
                            "clij_-1.9.0.1.jar",
                            "clij_-",
                            "plugins",
                            "https://sites.imagej.net/clij/plugins/clij_-1.9.0.1.jar-20210613085830",
                            "724139b2c163cd8a5e32fdf616347af0b3eecb03",
                            true),
                    new DependencySpec.JarRequirement(
                            "ClearCL",
                            "clij-clearcl-2.5.0.1.jar",
                            "clij-clearcl-",
                            "jars",
                            "https://sites.imagej.net/clij/jars/clij-clearcl-2.5.0.1.jar-20210613085830",
                            "4de6955a28c3a1c469b743cbf3f438c0db8e2fa3",
                            true),
                    new DependencySpec.JarRequirement(
                            "CLIJ core",
                            "clij-core-1.8.1.1.jar",
                            "clij-core-",
                            "jars",
                            "https://sites.imagej.net/clij/jars/clij-core-1.8.1.1.jar-20210725142738",
                            "56445e5f061eac19d79d933d470a5dc1d5f20068",
                            true),
                    new DependencySpec.JarRequirement(
                            "CLIJ core memory",
                            "clij-coremem-2.3.0.4.jar",
                            "clij-coremem-",
                            "jars",
                            "https://sites.imagej.net/clij/jars/clij-coremem-2.3.0.4.jar-20210506170808",
                            "4a657bc5a6e365c4dcfbaea5d6eafbc5e96a110f",
                            true),
                    new DependencySpec.JarRequirement(
                            "JOCL",
                            "jocl-2.0.5.jar",
                            "jocl-",
                            "jars",
                            "https://sites.imagej.net/clij/jars/jocl-2.0.5.jar-20250801124700",
                            "1718f599e8edc29a6d2a465ca43a963347a71669",
                            true),
                    new DependencySpec.JarRequirement(
                            "BridJ",
                            "bridj-0.7.0.jar",
                            "bridj-",
                            "jars",
                            "https://sites.imagej.net/clij/jars/bridj-0.7.0.jar-20181201213334",
                            "461c40ed578c92106579e370838ed4e224d0289e",
                            true),
                    new DependencySpec.JarRequirement(
                            "JavaCL",
                            "javacl-1.0.0-RC4.jar",
                            "javacl-",
                            "jars",
                            "https://sites.imagej.net/clij/jars/javacl-1.0.0-RC4.jar-20181201213040",
                            "3dd897f9fc9e85e21dad24b33b48f70f1a65a94a",
                            true),
                    new DependencySpec.JarRequirement(
                            "OpenCL4Java",
                            "opencl4java-1.0.0-RC4.jar",
                            "opencl4java-",
                            "jars",
                            "https://sites.imagej.net/clij/jars/opencl4java-1.0.0-RC4.jar-20181201213239",
                            "c7150b7cbe1237c61d81c93fa02926a65a5fa46e",
                            true),
                    new DependencySpec.JarRequirement(
                            "NativeLibs4Java utilities",
                            "nativelibs4java-utils-1.6.jar",
                            "nativelibs4java-utils-",
                            "jars",
                            "https://sites.imagej.net/clij/jars/nativelibs4java-utils-1.6.jar-20181201213239",
                            "2afd9ecca247bc7df95530bbc09674c311bc0f6f",
                            true),
                    new DependencySpec.JarRequirement(
                            "JavaCPP",
                            "javacpp-1.5.10.jar",
                            "javacpp-",
                            "jars",
                            "https://sites.imagej.net/Fiji/jars/javacpp-1.5.10.jar-20250122172943",
                            "afb6ae145e7563c66b677cb4896dd0197d49fce6",
                            true)
            ));

    private static final List<DependencySpec.JarRequirement> DECONVOLUTIONLAB2_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "DeconvolutionLab2",
                            "DeconvolutionLab_2.jar",
                            "DeconvolutionLab_",
                            "plugins",
                            "http://bigwww.epfl.ch/deconvolution/deconvolutionlab2/DeconvolutionLab_2.jar",
                            "c6bb56b77c0706b96aee4eba82c4459e694c108d",
                            false)
            ));

    private static final List<DependencySpec.JarRequirement> ITERATIVE_DECONVOLVE_3D_FILES =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "Iterative Deconvolve 3D",
                            "Iterative_Deconvolve_3D.class",
                            "Iterative_Deconvolve_3D-",
                            "plugins",
                            "https://www.optinav.info/download/Iterative_Deconvolve_3D.class",
                            "9579f73f0c897b3cef48dec1dedb49c263a2edb1",
                            false)
            ));

    private static final List<DependencySpec.JarRequirement> COLOC2_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "Coloc 2",
                            "Colocalisation_Analysis-3.1.0.jar",
                            "Colocalisation_Analysis-",
                            "plugins",
                            "https://sites.imagej.net/Fiji/plugins/Colocalisation_Analysis-3.1.0.jar-20240827125235",
                            "2755df5292d88d6f02e5828b28f4a48568178d5f",
                            false)
            ));

    private static final List<DependencySpec.JarRequirement> IMGLIB2_ALGORITHM_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "ImgLib2 Algorithm",
                            "imglib2-algorithm-0.18.1.jar",
                            "imglib2-algorithm-",
                            "jars",
                            "https://sites.imagej.net/Fiji/jars/imglib2-algorithm-0.18.1.jar-20260216133515",
                            "9fc27fe8d6fff3562db3402a25eebb6f7c9e0757",
                            false)
            ));

    private static final List<DependencySpec.JarRequirement> IMGLIB2_FFT_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "ImgLib2 FFT algorithms",
                            "imglib2-algorithm-fft-0.2.1.jar",
                            "imglib2-algorithm-fft-",
                            "jars",
                            "https://sites.imagej.net/Fiji/jars/imglib2-algorithm-fft-0.2.1.jar-20220912165414",
                            "2d7ec54a368401a1ae7a0ff622e294aa282d2f60",
                            false)
            ));

    private static final List<DependencySpec.JarRequirement> JTRANSFORMS_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "JTransforms",
                            "jtransforms-2.4.jar",
                            "jtransforms-",
                            "jars",
                            "https://sites.imagej.net/Fiji/jars/jtransforms-2.4.jar-20160121045452",
                            "9e52124b670340d47844a734e36765c3bc11b7f3",
                            false)
            ));

    /*
     * OrientationJ 2.0.7 embeds Maven metadata for ch.epfl.big:OrientationJ_:2.0.7,
     * but that coordinate is not published in the configured SciJava/Maven Central
     * repositories. Keep compile-time use behind a bridge/reflection until a resolvable
     * repository is available. Runtime install still uses the BIG-EPFL update-site jar.
     */
    private static final List<DependencySpec.JarRequirement> ORIENTATIONJ_JARS =
            Collections.unmodifiableList(Arrays.asList(
                    new DependencySpec.JarRequirement(
                            "OrientationJ",
                            "OrientationJ_-2.0.7.jar",
                            "OrientationJ_-",
                            "plugins",
                            "https://sites.imagej.net/BIG-EPFL/plugins/OrientationJ_-2.0.7.jar-20241021151847",
                            "a0662056fce60207ec4708e99d5413a6f820d054",
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
            if (spec == null || spec.getId() == null) {
                continue;
            }
            DependencyStatus status;
            try {
                status = spec.probe(context);
            } catch (RuntimeException e) {
                status = DependencyStatus.error("Probe failed for " + spec.getDisplayName()
                        + ": " + e.getClass().getSimpleName() + ": " + safeMessage(e));
            }
            snapshot.put(spec.getId(), status == null
                    ? DependencyStatus.error("Probe returned no status for " + spec.getDisplayName() + ".")
                    : status);
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
                addConflictIssues(issues, dir, requirement, null, ignorePrefixes);
                String otherFolder = "jars".equals(requirement.getFolder()) ? "plugins" : "jars";
                addConflictIssues(issues, new File(fijiDir, otherFolder), requirement, otherFolder, ignorePrefixes);
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
                disableWrongVersions(dir, requirement, dateSuffix, actions, null, ignorePrefixes);
                String otherFolder = "jars".equals(requirement.getFolder()) ? "plugins" : "jars";
                disableWrongVersions(new File(fijiDir, otherFolder), requirement, dateSuffix, actions, otherFolder, ignorePrefixes);
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

    static void setJarFileOpsForTesting(JarFileOps ops) {
        jarFileOps = ops == null ? DEFAULT_JAR_FILE_OPS : ops;
        SCHEDULED_DISABLES.clear();
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
                .nonFixableReason("Manual repair only. Close Fiji, remove stale FLASH plugin jars, move backup JARs out of Fiji.app, copy one fresh plugin jar, and restart.")
                .visibleInDependenciesDialog(true)
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

        specs.put(DependencyId.OBJECTS_COUNTER_3D_PLUS, DependencySpec.builder(
                        DependencyId.OBJECTS_COUNTER_3D_PLUS,
                        "3D Objects Counter+")
                .description("Final 3D Objects Counter+ plugin API reused by FLASH for Enhanced Classical segmentation and morphology filtering.")
                .affectedFeatures("Enhanced Classical segmentation", "enhanced classical morphology filtering", "trained RF Enhanced Classical base", "Set Up Configuration Enhanced Classical preview")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Class probe")
                .probe(classProbe(
                        "sc.fiji.oc3dplus.api.OC3DPlus",
                        "sc.fiji.oc3dplus.engine.ObjectsCounter3DWrapper"))
                .fixerStrategy(DependencySpec.FixerStrategy.NONE)
                .approxDownloadSizeBytes(OBJECTS_COUNTER_3D_PLUS_BYTES)
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason("Install 3D Objects Counter+ into Fiji, then restart Fiji.")
                .visibleInDependenciesDialog(true)
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

        specs.put(DependencyId.EPFL_PSF_GENERATOR_RUNTIME, DependencySpec.builder(
                        DependencyId.EPFL_PSF_GENERATOR_RUNTIME,
                        "EPFL PSF Generator")
                .description("EPFL PSF Generator plugin used to synthesize the point spread functions required by 3D deconvolution.")
                .affectedFeatures("3D Deconvolution PSF synthesis", "deconvolution preview", "deconvolution batch processing")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe + jar presence / version probe")
                .probe(composite(
                        classProbe("PSF_Generator"),
                        jarProbe(EPFL_PSF_GENERATOR_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(EPFL_PSF_GENERATOR_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install PSF Generator%s")
                .presentButtonLabel("Verify PSF Generator")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("3D Deconvolution")
                .jarRequirements(EPFL_PSF_GENERATOR_JARS)
                .build());

        specs.put(DependencyId.DECONV_CLIJ2_RUNTIME, DependencySpec.builder(
                        DependencyId.DECONV_CLIJ2_RUNTIME,
                        "CLIJ2 / clij2-fft runtime")
                .description("GPU deconvolution Java stack for the CLIJ2 engine. A usable OpenCL GPU is still required at run time.")
                .affectedFeatures("CLIJ2 3D deconvolution engine", "GPU Richardson-Lucy deconvolution", "GPU RL-TV deconvolution")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe + jar presence / version probe")
                .probe(composite(
                        classProbe(
                                "net.haesleinhuepf.clij2.CLIJ2",
                                "net.haesleinhuepf.clijx.plugins.DeconvolveRichardsonLucyFFT"),
                        jarProbe(DECONV_CLIJ2_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(DECONV_CLIJ2_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install CLIJ2 Deconvolution%s")
                .presentButtonLabel("Verify CLIJ2 Deconvolution")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("3D Deconvolution")
                .jarRequirements(DECONV_CLIJ2_JARS)
                .build());

        specs.put(DependencyId.DECONVOLUTIONLAB2_RUNTIME, DependencySpec.builder(
                        DependencyId.DECONVOLUTIONLAB2_RUNTIME,
                        "DeconvolutionLab2 runtime")
                .description("CPU deconvolution plugin used by the DeconvolutionLab2 engine.")
                .affectedFeatures("DeconvolutionLab2 3D deconvolution engine", "CPU Richardson-Lucy deconvolution", "CPU regularized deconvolution")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe + jar presence / version probe")
                .probe(composite(
                        classProbe("deconvolutionlab.Lab"),
                        jarProbe(DECONVOLUTIONLAB2_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(DECONVOLUTIONLAB2_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install DeconvolutionLab2%s")
                .presentButtonLabel("Verify DeconvolutionLab2")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("3D Deconvolution")
                .jarRequirements(DECONVOLUTIONLAB2_JARS)
                .build());

        specs.put(DependencyId.ITERATIVE_DECONVOLVE_3D_RUNTIME, DependencySpec.builder(
                        DependencyId.ITERATIVE_DECONVOLVE_3D_RUNTIME,
                        "Iterative Deconvolve 3D runtime")
                .description("Lightweight ImageJ 1.x Richardson-Lucy plugin used by the Iterative Deconvolve 3D engine.")
                .affectedFeatures("Iterative Deconvolve 3D engine", "CPU Richardson-Lucy deconvolution fallback")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("ImageJ command probe + class probe + plugin file presence probe")
                /*
                 * Iterative_Deconvolve_3D.class is a default-package, standalone .class file.
                 * Fiji's IJ.PluginClassLoader is the only loader that resolves such files; the
                 * FLASH plugin's own class loader cannot, so Class.forName("Iterative_Deconvolve_3D")
                 * from FLASH context returns ClassNotFoundException even when the file is correctly
                 * installed. ImageJ's command table is the authoritative source for whether
                 * IJ.run("Iterative Deconvolve 3D", ...) will work, so commandProbe is the primary
                 * detection here; classProbe is kept as an OR fallback so unit tests using a
                 * populated URLClassLoader continue to detect the class.
                 */
                .probe(composite(
                        anyOf(
                                commandProbe("Iterative Deconvolve 3D"),
                                classProbe("Iterative_Deconvolve_3D")),
                        jarProbe(ITERATIVE_DECONVOLVE_3D_FILES, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(ITERATIVE_DECONVOLVE_3D_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install Iterative Deconvolve 3D%s")
                .presentButtonLabel("Verify Iterative Deconvolve 3D")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("3D Deconvolution")
                .jarRequirements(ITERATIVE_DECONVOLVE_3D_FILES)
                .build());

        specs.put(DependencyId.COLOC2_RUNTIME, DependencySpec.builder(
                        DependencyId.COLOC2_RUNTIME,
                        "Coloc 2 runtime")
                .description("Fiji Coloc 2 algorithm classes used for selected intensity-spatial cross-channel analyses.")
                .affectedFeatures("intensity-spatial Pearson correlation", "intensity-spatial Manders coefficients", "intensity-spatial Costes significance")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe + jar presence / version probe")
                .probe(composite(
                        classProbe(
                                "sc.fiji.coloc.algorithms.PearsonsCorrelation",
                                "sc.fiji.coloc.algorithms.MandersColocalization",
                                "sc.fiji.coloc.algorithms.CostesSignificanceTest"),
                        jarProbe(COLOC2_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(COLOC2_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install Coloc 2%s")
                .presentButtonLabel("Verify Coloc 2 Runtime")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("Intensity Spatial")
                .jarRequirements(COLOC2_JARS)
                .build());

        specs.put(DependencyId.IMGLIB2_ALGORITHM_RUNTIME, DependencySpec.builder(
                        DependencyId.IMGLIB2_ALGORITHM_RUNTIME,
                        "ImgLib2 Algorithm runtime")
                .description("ImgLib2 algorithms used for selected intensity-spatial smoothing, distance, morphology, and structure-tensor paths.")
                .affectedFeatures("intensity-spatial smoothing", "intensity-spatial distance transforms", "intensity-spatial granularity", "2D intensity anisotropy")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe + jar presence / version probe")
                .probe(composite(
                        classProbe(
                                "net.imglib2.algorithm.gauss3.Gauss3",
                                "net.imglib2.algorithm.morphology.distance.DistanceTransform"),
                        jarProbe(IMGLIB2_ALGORITHM_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(IMGLIB2_ALGORITHM_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install ImgLib2 Algorithm%s")
                .presentButtonLabel("Verify ImgLib2 Algorithm Runtime")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("Intensity Spatial")
                .jarRequirements(IMGLIB2_ALGORITHM_JARS)
                .build());

        specs.put(DependencyId.IMGLIB2_FFT_RUNTIME, DependencySpec.builder(
                        DependencyId.IMGLIB2_FFT_RUNTIME,
                        "ImgLib2 FFT runtime")
                .description("ImgLib2 FFT algorithms used for selected intensity-spatial frequency and autocorrelation analyses.")
                .affectedFeatures("FFT-accelerated Moran's I", "variogram analysis", "periodicity analysis")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe + jar presence / version probe")
                .probe(composite(
                        classProbe("net.imglib2.algorithm.fft2.FFT", "net.imglib2.algorithm.fft2.FFTMethods"),
                        jarProbe(IMGLIB2_FFT_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(IMGLIB2_FFT_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install ImgLib2 FFT%s")
                .presentButtonLabel("Verify ImgLib2 FFT Runtime")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("Intensity Spatial")
                .jarRequirements(IMGLIB2_FFT_JARS)
                .build());

        specs.put(DependencyId.JTRANSFORMS_RUNTIME, DependencySpec.builder(
                        DependencyId.JTRANSFORMS_RUNTIME,
                        "JTransforms runtime")
                .description("Pure Java FFT runtime used for selected intensity-spatial cross-correlation and periodicity analyses.")
                .affectedFeatures("cross-correlation functions", "periodicity analysis", "FFT-based Moran's I")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe + jar presence / version probe")
                .probe(composite(
                        classProbe("edu.emory.mathcs.jtransforms.fft.DoubleFFT_2D"),
                        jarProbe(JTRANSFORMS_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(JTRANSFORMS_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install JTransforms%s")
                .presentButtonLabel("Verify JTransforms Runtime")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("Intensity Spatial")
                .jarRequirements(JTRANSFORMS_JARS)
                .build());

        specs.put(DependencyId.ORIENTATIONJ_RUNTIME, DependencySpec.builder(
                        DependencyId.ORIENTATIONJ_RUNTIME,
                        "OrientationJ runtime")
                .description("BIG-EPFL OrientationJ structure-tensor classes used only by optional native-3D intensity anisotropy.")
                .affectedFeatures("native-3D intensity anisotropy")
                .criticality(DependencySpec.Criticality.OPTIONAL_FEATURE)
                .detectionStrategyLabel("Composite class probe + jar presence / version probe")
                .probe(composite(
                        classProbe("orientation.StructureTensor"),
                        jarProbe(ORIENTATIONJ_JARS, Collections.<String>emptyList())))
                .fixerStrategy(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD)
                .approxDownloadSizeBytes(ORIENTATIONJ_RUNTIME_BYTES)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install OrientationJ%s")
                .presentButtonLabel("Verify OrientationJ Runtime")
                .visibleInDependenciesDialog(true)
                .dialogSectionLabel("Intensity Spatial")
                .jarRequirements(ORIENTATIONJ_JARS)
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

    private static DependencySpec.Probe anyOf(final DependencySpec.Probe... probes) {
        return new DependencySpec.Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                if (probes == null || probes.length == 0) {
                    return DependencyStatus.error("No probes were configured for this dependency.");
                }
                List<String> missing = new ArrayList<String>();
                List<String> errors = new ArrayList<String>();
                for (DependencySpec.Probe probe : probes) {
                    if (probe == null) {
                        continue;
                    }
                    DependencyStatus status = probe.probe(context);
                    if (status == null) {
                        continue;
                    }
                    if (status.isPresent()) {
                        return status;
                    }
                    String detail = status.getDetailMessage();
                    if (status.isError()) {
                        errors.add(detail == null ? "" : detail);
                    } else {
                        missing.add(detail == null ? "" : detail);
                    }
                }
                if (!missing.isEmpty()) {
                    return DependencyStatus.missing(joinLines(missing));
                }
                if (!errors.isEmpty()) {
                    return DependencyStatus.error(joinLines(errors));
                }
                return DependencyStatus.missing("No probe could detect this dependency.");
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
                CompletableFuture<Status> probe = CellposeRuntime.probeAsync();
                if (!probe.isDone()) {
                    return DependencyStatus.checking(
                            "Checking Cellpose runtime in the background. "
                                    + "Open Dependencies or click Refresh to see the resolved status.");
                }

                Status status;
                try {
                    status = probe.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return DependencyStatus.error("Cellpose runtime probe was interrupted.");
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    return DependencyStatus.error("Cellpose runtime probe failed: "
                            + cause.getClass().getSimpleName() + ": " + safeMessage(cause));
                }
                if (status == null) {
                    return DependencyStatus.error("Cellpose runtime probe returned no status.");
                }
                if (status.unknown) {
                    return DependencyStatus.checking(
                            "Checking Cellpose runtime in the background. "
                                    + "Open Dependencies or click Refresh to see the resolved status.");
                }
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
                File liveJar = null;
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
                        liveJar = file;
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

                List<String> missingEntries = findMissingFlashJarEntries(liveJar);
                if (!missingEntries.isEmpty()) {
                    return DependencyStatus.error("Live FLASH plugin JAR is incomplete: "
                            + liveJars.get(0) + " missing " + joinComma(missingEntries)
                            + ". Close Fiji, replace it with a freshly built FLASH JAR, and restart Fiji.");
                }

                List<String> nestedJars = findFlashJarsOutsidePluginsRoot(fijiDir, pluginsDir);
                if (!nestedJars.isEmpty()) {
                    return DependencyStatus.error("FLASH plugin jar copies detected outside Fiji plugins/ root: "
                            + joinComma(nestedJars)
                            + ". Move backup copies outside Fiji.app, then restart Fiji.");
                }

                return DependencyStatus.present("Detected live plugin jar: " + liveJars.get(0));
            }
        };
    }

    private static List<String> findMissingFlashJarEntries(File liveJar) {
        List<String> missing = new ArrayList<String>();
        if (liveJar == null || !liveJar.isFile()) {
            missing.add("plugin file");
            return missing;
        }

        String[] requiredEntries = {
                "flash/pipeline/FLASH_Pipeline.class",
                "flash/pipeline/intelligence/PreFlightChecks.class",
                "flash/pipeline/intelligence/PreFlightChecks$DirectoryFileScan.class",
                "flash/pipeline/recipes/PipelineRecipeIO.class",
                "pipeline_recipes/standard-3d-intensity.json",
                "pipeline_recipes/quick-cell-count.json",
                "pipeline_recipes/presentation.json",
                "pipeline_recipes/fast-presentable-results.json",
                "pipeline_recipes/full-pipeline.json"
        };

        JarFile jar = null;
        try {
            jar = new JarFile(liveJar);
            for (String entry : requiredEntries) {
                if (jar.getEntry(entry) == null) {
                    missing.add(entry);
                }
            }
        } catch (IOException e) {
            missing.add("readable JAR contents (" + safeMessage(e) + ")");
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                }
            }
        }
        return missing;
    }

    private static List<String> findFlashJarsOutsidePluginsRoot(File fijiDir, File pluginsDir) {
        List<String> matches = new ArrayList<String>();
        collectFlashJarsOutsidePluginsRoot(fijiDir, canonicalPath(fijiDir), canonicalPath(pluginsDir), matches);
        Collections.sort(matches);
        return matches;
    }

    private static void collectFlashJarsOutsidePluginsRoot(File file,
                                                           String fijiRootPath,
                                                           String pluginsRootPath,
                                                           List<String> matches) {
        if (file == null || matches == null || isScheduledForDisable(file)) {
            return;
        }
        String path = canonicalPath(file);
        if (file.isFile()) {
            String name = file.getName();
            if (path.equals(new File(pluginsRootPath, name).getPath())) {
                return;
            }
            if (isFlashPluginJarName(name) && !isDirectChildOf(file, pluginsRootPath)) {
                matches.add(relativePath(fijiRootPath, path));
            }
            return;
        }
        if (!file.isDirectory()) {
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectFlashJarsOutsidePluginsRoot(child, fijiRootPath, pluginsRootPath, matches);
        }
    }

    private static boolean isDirectChildOf(File file, String parentPath) {
        File parent = file == null ? null : file.getParentFile();
        return parent != null && canonicalPath(parent).equals(parentPath);
    }

    private static boolean isFlashPluginJarName(String name) {
        if (name == null || !name.endsWith(".jar")) {
            return false;
        }
        return name.startsWith("FLASH-")
                || name.startsWith("original-FLASH-")
                || name.contains("IHF-Analysis-Pipeline-");
    }

    private static String canonicalPath(File file) {
        if (file == null) {
            return "";
        }
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static String relativePath(String rootPath, String childPath) {
        if (rootPath != null && childPath != null
                && childPath.startsWith(rootPath)
                && childPath.length() > rootPath.length()) {
            String relative = childPath.substring(rootPath.length());
            while (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }
            return relative;
        }
        return childPath;
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
            if (isScheduledForDisable(file)) {
                continue;
            }
            if (!name.endsWith(".jar")) {
                continue;
            }
            if (shouldIgnore(name, ignorePrefixes)) {
                continue;
            }
            if (matchesVersionedJarPrefix(name, prefix)) {
                return file;
            }
        }
        return null;
    }

    private static void addConflictIssues(List<String> issues,
                                          File dir,
                                          DependencySpec.JarRequirement requirement,
                                          String folderLabel,
                                          List<String> ignorePrefixes) {
        List<String> conflicts = findConflictingJars(dir, requirement, ignorePrefixes);
        if (conflicts.isEmpty()) {
            return;
        }
        if (folderLabel == null) {
            issues.add(requirement.getLabel() + ": conflicting extra jar(s) beside "
                    + requirement.getExpectedFile() + ": " + joinComma(conflicts));
        } else {
            issues.add(requirement.getLabel() + ": conflicting extra jar(s) in "
                    + folderLabel + "/ while using " + requirement.getExpectedFile()
                    + ": " + joinComma(conflicts));
        }
    }

    private static List<String> findConflictingJars(File dir,
                                                    DependencySpec.JarRequirement requirement,
                                                    List<String> ignorePrefixes) {
        List<String> conflicts = new ArrayList<String>();
        File[] candidates = dir.listFiles();
        if (candidates == null) {
            return conflicts;
        }
        for (File file : candidates) {
            String name = file.getName();
            if (!file.isFile()) {
                continue;
            }
            if (isScheduledForDisable(file)) {
                continue;
            }
            if (!name.endsWith(".jar")) {
                continue;
            }
            if (shouldIgnore(name, ignorePrefixes)) {
                continue;
            }
            if (!matchesVersionedJarPrefix(name, requirement.getMatchPrefix())) {
                continue;
            }
            if (name.equals(requirement.getExpectedFile())) {
                continue;
            }
            conflicts.add(name);
        }
        Collections.sort(conflicts);
        return conflicts;
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
            if (isScheduledForDisable(file)) {
                continue;
            }
            if (!name.endsWith(".jar")) {
                continue;
            }
            if (shouldIgnore(name, ignorePrefixes)) {
                continue;
            }
            if (!matchesVersionedJarPrefix(name, requirement.getMatchPrefix())) {
                continue;
            }
            if (name.equals(requirement.getExpectedFile())) {
                continue;
            }

            File disabled = uniqueDisabledFile(dir, name, dateSuffix);
            if (jarFileOps.rename(file, disabled)) {
                actions.add(folderLabel == null
                        ? "Disabled: " + name
                        : "Disabled: " + name + " (from " + folderLabel + "/)");
            } else if (jarFileOps.scheduleDisable(file, disabled)) {
                SCHEDULED_DISABLES.add(fileKey(file));
                actions.add(folderLabel == null
                        ? "Scheduled disable after Fiji closes: " + name
                        : "Scheduled disable after Fiji closes: " + name + " (from " + folderLabel + "/)");
            } else {
                actions.add("FAILED to disable: " + name
                        + " (the jar stayed locked and could not be scheduled for after-exit cleanup; "
                        + "close Fiji, rename or remove this jar manually, then restart Fiji.)");
            }
        }
    }

    public static boolean hasDisabledJarActions(List<String> actions) {
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            String trimmed = action == null ? "" : action.trim();
            if (trimmed.startsWith("Disabled:")
                    || trimmed.startsWith("Scheduled disable after Fiji closes:")) {
                return true;
            }
        }
        return false;
    }

    public static String disabledJarRestoreGuidance(String runtimeName) {
        String label = runtimeName == null || runtimeName.trim().isEmpty()
                ? "this runtime"
                : runtimeName.trim();
        return "Disabled jars are not deleted. If another Fiji tool needs a newer jar later, "
                + "close Fiji, rename that file from .jar.disabled-YYYYMMDD back to .jar, "
                + "and restart Fiji. Re-run Auto-Fix for " + label
                + " before using the pinned FLASH workflow again.";
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

    private static boolean matchesVersionedJarPrefix(String fileName, String prefix) {
        if (fileName == null || prefix == null || !fileName.startsWith(prefix)) {
            return false;
        }
        if (fileName.length() <= prefix.length()) {
            return false;
        }
        return Character.isDigit(fileName.charAt(prefix.length()));
    }

    private static File uniqueDisabledFile(File dir, String name, String dateSuffix) {
        File candidate = new File(dir, name + ".disabled-" + dateSuffix);
        if (!candidate.exists()) {
            return candidate;
        }
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, name + ".disabled-" + dateSuffix + "-" + i);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, name + ".disabled-" + dateSuffix + "-" + System.currentTimeMillis());
    }

    private static boolean isScheduledForDisable(File file) {
        return file != null && SCHEDULED_DISABLES.contains(fileKey(file));
    }

    private static String fileKey(File file) {
        if (file == null) {
            return "";
        }
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static boolean scheduleWindowsDisableAfterExit(File source, File disabled) {
        if (!isWindows() || source == null || disabled == null) {
            return false;
        }
        try {
            File script = File.createTempFile("flash-runtime-disable-", ".ps1");
            File log = new File(source.getParentFile(), "FLASH-runtime-repair.log");
            writeDeferredDisableScript(script);

            List<String> command = new ArrayList<String>();
            command.add("powershell.exe");
            command.add("-NoProfile");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-WindowStyle");
            command.add("Hidden");
            command.add("-File");
            command.add(script.getAbsolutePath());
            command.add(currentProcessId());
            command.add(source.getAbsolutePath());
            command.add(disabled.getAbsolutePath());
            command.add(log.getAbsolutePath());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            builder.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeDeferredDisableScript(File script) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(script), StandardCharsets.UTF_8))) {
            writer.write("param([string]$ParentPid,[string]$SourcePath,[string]$DestPath,[string]$LogPath)\n");
            writer.write("$ErrorActionPreference = 'Stop'\n");
            writer.write("function Write-RepairLog([string]$Message) {\n");
            writer.write("  $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'\n");
            writer.write("  Add-Content -LiteralPath $LogPath -Value \"$stamp $Message\" -ErrorAction SilentlyContinue\n");
            writer.write("}\n");
            writer.write("try {\n");
            writer.write("  Write-RepairLog \"Waiting to disable locked Fiji jar: $SourcePath\"\n");
            writer.write("  if ($ParentPid -match '^[0-9]+$') {\n");
            writer.write("    while (Get-Process -Id ([int]$ParentPid) -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 2 }\n");
            writer.write("  }\n");
            writer.write("  for ($i = 0; $i -lt 300; $i++) {\n");
            writer.write("    if (-not (Test-Path -LiteralPath $SourcePath)) { Write-RepairLog 'Source already gone.'; exit 0 }\n");
            writer.write("    try {\n");
            writer.write("      Rename-Item -LiteralPath $SourcePath -NewName (Split-Path -Leaf $DestPath) -Force\n");
            writer.write("      Write-RepairLog \"Disabled $SourcePath -> $DestPath\"\n");
            writer.write("      Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue\n");
            writer.write("      exit 0\n");
            writer.write("    } catch {\n");
            writer.write("      Start-Sleep -Seconds 1\n");
            writer.write("    }\n");
            writer.write("  }\n");
            writer.write("  Write-RepairLog \"FAILED to disable $SourcePath after Fiji closed.\"\n");
            writer.write("  exit 1\n");
            writer.write("} catch {\n");
            writer.write("  Write-RepairLog \"FAILED to disable ${SourcePath}: $($_.Exception.Message)\"\n");
            writer.write("  exit 1\n");
            writer.write("}\n");
        }
    }

    private static String currentProcessId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        if (runtimeName == null) {
            return "";
        }
        int at = runtimeName.indexOf('@');
        String pid = at >= 0 ? runtimeName.substring(0, at) : runtimeName;
        return pid == null ? "" : pid.trim();
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static void downloadFile(String urlStr, File dest, String expectedSha1) throws Exception {
        File temp = new File(dest.getParentFile(), dest.getName() + ".download");
        URL url = new URL(urlStr);
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            if (temp.exists()) {
                temp.delete();
            }
            throw e;
        }

        try {
            if (temp.length() < 1000L) {
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
        try (InputStream in = new FileInputStream(file)) {
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
