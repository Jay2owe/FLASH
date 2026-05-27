package flash.pipeline.stardist;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeCheckerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void checkReportsConflictingStarDistJarBesideExpectedJar() throws Exception {
        File fijiDir = completeStarDistRuntime("stardist-runtime-conflict");
        File plugins = new File(fijiDir, "plugins");
        touch(plugins, "StarDist_-0.3.0 (cloud conflicted copy).jar");
        File jars = new File(fijiDir, "jars");
        touch(jars, "protobuf-java-4.28.2.jar");

        List<String> issues = RuntimeChecker.check(fijiDir);

        assertTrue(containsIssue(issues, "StarDist: conflicting extra jar(s) beside StarDist_-0.3.0.jar"));
        assertTrue(containsIssue(issues, "StarDist_-0.3.0 (cloud conflicted copy).jar"));
        assertTrue(containsIssue(issues, "protobuf-java: conflicting extra jar(s) beside protobuf-java-3.5.1.jar"));
        assertTrue(containsIssue(issues, "protobuf-java-4.28.2.jar"));
    }

    @Test
    public void repairDisablesConflictingStarDistJarEvenWhenExpectedJarExists() throws Exception {
        File fijiDir = completeStarDistRuntime("stardist-runtime-repair-conflict");
        File plugins = new File(fijiDir, "plugins");
        File duplicate = touch(plugins, "StarDist_-0.3.0 (cloud conflicted copy).jar");

        List<String> actions = RuntimeChecker.repair(fijiDir);

        assertFalse(duplicate.exists());
        assertTrue(containsIssue(actions, "Disabled: StarDist_-0.3.0 (cloud conflicted copy).jar"));
        assertTrue(RuntimeChecker.check(fijiDir).isEmpty());
    }

    @Test
    public void trackMateStarDistJarIsNotMisreportedAsTrackMateConflict() throws Exception {
        File fijiDir = completeStarDistRuntime("stardist-runtime-trackmate-prefix");

        List<String> issues = RuntimeChecker.check(fijiDir);

        assertTrue(issues.isEmpty());
    }

    private File completeStarDistRuntime(String prefix) throws Exception {
        File fijiDir = temp.newFolder(prefix);
        File jars = new File(fijiDir, "jars");
        File plugins = new File(fijiDir, "plugins");
        assertTrue(jars.mkdirs());
        assertTrue(plugins.mkdirs());

        touch(jars, "TrackMate-7.14.0.jar");
        touch(jars, "TrackMate-StarDist-1.2.1.jar");
        touch(jars, "csbdeep-0.6.0.jar");
        touch(jars, "imagej-tensorflow-1.1.5.jar");
        touch(jars, "proto-1.15.0.jar");
        touch(jars, "protobuf-java-3.5.1.jar");
        touch(plugins, "StarDist_-0.3.0.jar");
        return fijiDir;
    }

    private static File touch(File dir, String name) throws Exception {
        File file = new File(dir, name);
        assertTrue(file.createNewFile());
        return file;
    }

    private static boolean containsIssue(List<String> issues, String needle) {
        for (String issue : issues) {
            if (issue.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
