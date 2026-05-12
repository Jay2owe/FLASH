package flash.pipeline.runtime;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DependencyRegistryTest {

    @Test
    public void everyDependencyIdHasExactlyOneSpec() {
        Map<DependencyId, DependencySpec> lookup = DependencyRegistry.lookup();
        assertEquals(DependencyId.values().length, lookup.size());

        Set<DependencyId> seen = EnumSet.noneOf(DependencyId.class);
        Set<DependencySpec> uniqueSpecs = new HashSet<DependencySpec>();
        for (DependencySpec spec : DependencyRegistry.all()) {
            assertSame(spec, lookup.get(spec.getId()));
            assertTrue("Duplicate spec for " + spec.getId(), seen.add(spec.getId()));
            assertTrue("Spec instance reused for " + spec.getId(), uniqueSpecs.add(spec));
        }

        assertEquals(EnumSet.allOf(DependencyId.class), seen);
    }

    @Test
    public void formatApproxSizeHandlesZeroBoundariesAndUnknownValues() {
        assertEquals("(~0 B)", DependencyRegistry.formatApproxSize(0));
        assertEquals("", DependencyRegistry.formatApproxSize(-1));
        assertEquals("(~1 KB)", DependencyRegistry.formatApproxSize(1));
        assertEquals("(~1 KB)", DependencyRegistry.formatApproxSize(1024));
        assertEquals("(~1 MB)", DependencyRegistry.formatApproxSize(1024 * 1024));
        assertEquals("(~1.5 MB)", DependencyRegistry.formatApproxSize(1536 * 1024));
        assertEquals("(~1 GB)", DependencyRegistry.formatApproxSize(1024L * 1024L * 1024L));
        assertEquals("(~2.5 GB)", DependencyRegistry.formatApproxSize(2684354560L));
    }

    @Test
    public void specFlagsMatchDependencyAuditTable() {
        assertFlags(DependencyId.PLUGIN_JAR_INTEGRITY, false, true, false);
        assertFlags(DependencyId.IMAGEJ_RUNTIME, false, true, false);
        assertFlags(DependencyId.BIO_FORMATS_RUNTIME, true, true, true);
        assertFlags(DependencyId.OBJECTS_COUNTER_3D, true, true, true);
        assertFlags(DependencyId.MCIB3D_CORE, false, true, true);
        assertFlags(DependencyId.STARDIST_RUNTIME, true, true, true);
        assertFlags(DependencyId.TENSORFLOW_NATIVE_RUNTIME, true, true, true);
        assertFlags(DependencyId.APACHE_POI_RUNTIME, true, true, true);
        assertFlags(DependencyId.CELLPOSE_RUNTIME, true, false, true);
        assertFlags(DependencyId.COLOC2_RUNTIME, true, true, true);
        assertFlags(DependencyId.IMGLIB2_ALGORITHM_RUNTIME, true, true, true);
        assertFlags(DependencyId.IMGLIB2_FFT_RUNTIME, true, true, true);
        assertFlags(DependencyId.JTRANSFORMS_RUNTIME, true, true, true);
        assertFlags(DependencyId.ORIENTATIONJ_RUNTIME, true, true, true);
        assertFlags(DependencyId.JTS_CORE, true, true, true);
    }

    @Test
    public void intensitySpatialDependenciesHavePinnedJarMetadata() {
        assertPinnedJar(DependencyId.COLOC2_RUNTIME,
                "Colocalisation_Analysis-3.1.0.jar",
                "https://sites.imagej.net/Fiji/plugins/Colocalisation_Analysis-3.1.0.jar-20240827125235",
                "2755df5292d88d6f02e5828b28f4a48568178d5f");
        assertPinnedJar(DependencyId.IMGLIB2_ALGORITHM_RUNTIME,
                "imglib2-algorithm-0.18.1.jar",
                "https://sites.imagej.net/Fiji/jars/imglib2-algorithm-0.18.1.jar-20260216133515",
                "9fc27fe8d6fff3562db3402a25eebb6f7c9e0757");
        assertPinnedJar(DependencyId.IMGLIB2_FFT_RUNTIME,
                "imglib2-algorithm-fft-0.2.1.jar",
                "https://sites.imagej.net/Fiji/jars/imglib2-algorithm-fft-0.2.1.jar-20220912165414",
                "2d7ec54a368401a1ae7a0ff622e294aa282d2f60");
        assertPinnedJar(DependencyId.JTRANSFORMS_RUNTIME,
                "jtransforms-2.4.jar",
                "https://sites.imagej.net/Fiji/jars/jtransforms-2.4.jar-20160121045452",
                "9e52124b670340d47844a734e36765c3bc11b7f3");
        assertPinnedJar(DependencyId.ORIENTATIONJ_RUNTIME,
                "OrientationJ_-2.0.7.jar",
                "https://sites.imagej.net/BIG-EPFL/plugins/OrientationJ_-2.0.7.jar-20241021151847",
                "a0662056fce60207ec4708e99d5413a6f820d054");
    }

    @Test
    public void repairSchedulesLockedConflictingJarAndCurrentSessionIgnoresIt() throws Exception {
        File fijiDir = Files.createTempDirectory("locked-pinned-jar").toFile();
        File jars = new File(fijiDir, "jars");
        assertTrue(jars.mkdirs());
        touch(jars, "demo-1.0.jar");
        File duplicate = touch(jars, "demo-2.0.jar");

        DependencyRegistry.setJarFileOpsForTesting(new DependencyRegistry.JarFileOps() {
            @Override
            public boolean rename(File source, File disabled) {
                return false;
            }

            @Override
            public boolean scheduleDisable(File source, File disabled) {
                return true;
            }
        });
        try {
            List<DependencySpec.JarRequirement> requirements = Collections.singletonList(
                    new DependencySpec.JarRequirement(
                            "Demo",
                            "demo-1.0.jar",
                            "demo-",
                            "jars",
                            "",
                            false));

            List<String> actions = DependencyRegistry.repairJarRequirements(
                    fijiDir, requirements, Collections.<String>emptyList());

            assertTrue("Locked jar should still exist until Fiji exits", duplicate.exists());
            assertTrue(contains(actions, "Scheduled disable after Fiji closes: demo-2.0.jar"));
            assertTrue(DependencyRegistry.checkJarRequirements(
                    fijiDir, requirements, Collections.<String>emptyList()).isEmpty());
        } finally {
            DependencyRegistry.setJarFileOpsForTesting(null);
        }
    }

    @Test
    public void checkReportsWrongVersionJarFromAlternateFijiFolder() throws Exception {
        File fijiDir = Files.createTempDirectory("alternate-folder-pinned-jar").toFile();
        File jars = new File(fijiDir, "jars");
        File plugins = new File(fijiDir, "plugins");
        assertTrue(jars.mkdirs());
        assertTrue(plugins.mkdirs());
        touch(jars, "demo-1.0.jar");
        touch(plugins, "demo-2.0.jar");

        List<DependencySpec.JarRequirement> requirements = Collections.singletonList(
                new DependencySpec.JarRequirement(
                        "Demo",
                        "demo-1.0.jar",
                        "demo-",
                        "jars",
                        "",
                        false));

        List<String> issues = DependencyRegistry.checkJarRequirements(
                fijiDir, requirements, Collections.<String>emptyList());

        assertTrue(contains(issues, "Demo: conflicting extra jar(s) in plugins/"));
        assertTrue(contains(issues, "demo-2.0.jar"));
    }

    @Test
    public void deferredDisableScriptEscapesSourcePathBeforeColon() throws Exception {
        File script = File.createTempFile("flash-runtime-disable-test-", ".ps1");
        Method method = DependencyRegistry.class.getDeclaredMethod("writeDeferredDisableScript", File.class);
        method.setAccessible(true);

        method.invoke(null, script);

        String text = new String(Files.readAllBytes(script.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("PowerShell treats $SourcePath: as an invalid scoped variable", text.contains("$SourcePath:"));
        assertTrue(text.contains("${SourcePath}:"));
    }

    @Test
    public void failedDisableMessageDoesNotAskWhetherFijiIsRunning() throws Exception {
        File fijiDir = Files.createTempDirectory("failed-disable-message").toFile();
        File jars = new File(fijiDir, "jars");
        assertTrue(jars.mkdirs());
        touch(jars, "demo-1.0.jar");
        touch(jars, "demo-2.0.jar");

        DependencyRegistry.setJarFileOpsForTesting(new DependencyRegistry.JarFileOps() {
            @Override
            public boolean rename(File source, File disabled) {
                return false;
            }

            @Override
            public boolean scheduleDisable(File source, File disabled) {
                return false;
            }
        });
        try {
            List<DependencySpec.JarRequirement> requirements = Collections.singletonList(
                    new DependencySpec.JarRequirement(
                            "Demo",
                            "demo-1.0.jar",
                            "demo-",
                            "jars",
                            "",
                            false));

            List<String> actions = DependencyRegistry.repairJarRequirements(
                    fijiDir, requirements, Collections.<String>emptyList());

            assertTrue(contains(actions, "FAILED to disable: demo-2.0.jar"));
            assertFalse(join(actions).contains("is Fiji running"));
            assertTrue(join(actions).contains("jar stayed locked"));
        } finally {
            DependencyRegistry.setJarFileOpsForTesting(null);
        }
    }

    @Test
    public void disabledJarRestoreGuidanceExplainsHowToReEnableVersions() {
        String guidance = DependencyRegistry.disabledJarRestoreGuidance("StarDist");

        assertTrue(guidance.contains(".jar.disabled-YYYYMMDD back to .jar"));
        assertTrue(guidance.contains("another Fiji tool needs a newer jar"));
        assertTrue(guidance.contains("Re-run Auto-Fix for StarDist"));
    }

    private static void assertFlags(DependencyId id,
                                    boolean fixable,
                                    boolean restartRequired,
                                    boolean uiVisible) {
        DependencySpec spec = DependencyRegistry.get(id);
        assertEquals(id + " fixable", fixable, spec.isFixableInApp());
        assertEquals(id + " restart", restartRequired, spec.isRestartRequired());
        assertEquals(id + " visible", uiVisible, spec.isVisibleInDependenciesDialog());
    }

    private static void assertPinnedJar(DependencyId id,
                                        String expectedFile,
                                        String downloadUrl,
                                        String expectedSha1) {
        DependencySpec spec = DependencyRegistry.get(id);
        assertTrue(id + " should be direct-jar fixable", spec.isFixableInApp());
        assertEquals(DependencySpec.FixerStrategy.DIRECT_JAR_DOWNLOAD, spec.getFixerStrategy());
        assertEquals(1, spec.getJarRequirements().size());
        assertTrue(id + " should report download size", spec.getApproxDownloadSizeBytes() > 0L);

        DependencySpec.JarRequirement jar = spec.getJarRequirements().get(0);
        assertEquals(expectedFile, jar.getExpectedFile());
        assertEquals(downloadUrl, jar.getDownloadUrl());
        assertEquals(expectedSha1, jar.getExpectedSha1());
        assertEquals(40, jar.getExpectedSha1().length());
    }

    private static File touch(File dir, String name) throws Exception {
        File file = new File(dir, name);
        assertTrue(file.createNewFile());
        return file;
    }

    private static boolean contains(List<String> lines, String needle) {
        for (String line : lines) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
