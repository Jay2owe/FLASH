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
        assertFlags(DependencyId.JTS_CORE, true, true, true);
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
