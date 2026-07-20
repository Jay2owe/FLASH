package flash.pipeline.project;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProjectPathResolverTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void projectJsonFromSelectedLocation_acceptsProjectRoot() throws Exception {
        File outputRoot = temp.newFolder("project-root");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFileIO.write(settingsDir, new ProjectFile());

        File projectJson = ProjectPathResolver.projectJsonFromSelectedLocation(outputRoot);

        assertEquals(new File(settingsDir, ProjectFileIO.FILE_NAME).getCanonicalPath(),
                projectJson.getCanonicalPath());
    }

    @Test
    public void projectJsonFromSelectedLocation_acceptsFlashFolderDirectly() throws Exception {
        File outputRoot = temp.newFolder("project-root");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFileIO.write(settingsDir, new ProjectFile());
        File flashFolder = new File(outputRoot, FlashProjectLayout.FLASH_DIR);

        File projectJson = ProjectPathResolver.projectJsonFromSelectedLocation(flashFolder);

        assertEquals(new File(settingsDir, ProjectFileIO.FILE_NAME).getCanonicalPath(),
                projectJson.getCanonicalPath());
    }

    @Test
    public void projectJsonFromSelectedLocation_acceptsSettingsDir() throws Exception {
        File outputRoot = temp.newFolder("project-root");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFileIO.write(settingsDir, new ProjectFile());

        File projectJson = ProjectPathResolver.projectJsonFromSelectedLocation(settingsDir);

        assertEquals(new File(settingsDir, ProjectFileIO.FILE_NAME).getCanonicalPath(),
                projectJson.getCanonicalPath());
    }

    @Test
    public void projectJsonFromSelectedLocation_rejectsPlainFolder() throws Exception {
        assertNull(ProjectPathResolver.projectJsonFromSelectedLocation(temp.newFolder("plain")));
    }

    @Test
    public void resolveProjectJsonNear_acceptsProjectRootAndFlashFolder() throws Exception {
        File outputRoot = temp.newFolder("project-root");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFileIO.write(settingsDir, new ProjectFile());
        File expected = new File(settingsDir, ProjectFileIO.FILE_NAME);
        File flashFolder = new File(outputRoot, FlashProjectLayout.FLASH_DIR);

        assertEquals(expected.getCanonicalPath(),
                ProjectPathResolver.resolveProjectJsonNear(outputRoot).getCanonicalPath());
        assertEquals(expected.getCanonicalPath(),
                ProjectPathResolver.resolveProjectJsonNear(flashFolder).getCanonicalPath());
    }

    @Test
    public void resolveProjectJsonNear_acceptsProjectJsonFileItself() throws Exception {
        File outputRoot = temp.newFolder("project-root");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFileIO.write(settingsDir, new ProjectFile());
        File projectJson = new File(settingsDir, ProjectFileIO.FILE_NAME);

        assertEquals(projectJson.getCanonicalPath(),
                ProjectPathResolver.resolveProjectJsonNear(projectJson).getCanonicalPath());
    }

    @Test
    public void resolveProjectJsonNear_climbsFromFolderInsideProject() throws Exception {
        // The user picked (or the chooser returned) a folder a couple levels
        // deep inside the project. We still find the enclosing project.json.
        File outputRoot = temp.newFolder("project-root");
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFileIO.write(settingsDir, new ProjectFile());
        File expected = new File(settingsDir, ProjectFileIO.FILE_NAME);
        File deepInside = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .tablesObjectsWriteDir();
        assertTrue(deepInside.mkdirs());

        assertEquals(expected.getCanonicalPath(),
                ProjectPathResolver.resolveProjectJsonNear(deepInside).getCanonicalPath());
    }

    @Test
    public void resolveProjectJsonNear_returnsNullForUnrelatedFolder() throws Exception {
        assertNull(ProjectPathResolver.resolveProjectJsonNear(temp.newFolder("unrelated")));
        assertNull(ProjectPathResolver.resolveProjectJsonNear(null));
    }

    @Test
    public void addRelativePathHints_recordsSourcesUnderOutputRoot() throws Exception {
        File outputRoot = temp.newFolder("output");
        File sourceDir = new File(outputRoot, "WT");
        assertTrue(sourceDir.mkdirs());
        File source = new File(sourceDir, "Exp-A_LH_X.lif");
        assertTrue(source.createNewFile());

        ProjectFile project = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = source.getAbsolutePath();
        project.items.add(item);

        ProjectPathResolver.addRelativePathHints(project, outputRoot);

        assertEquals("WT/Exp-A_LH_X.lif",
                project.items.get(0).extras.get(ProjectPathResolver.K_PATH_RELATIVE_TO_OUTPUT_ROOT));
    }

    @Test
    public void relocateForLoad_usesRelativeHintWhenAbsolutePathMoved() throws Exception {
        File outputRoot = temp.newFolder("new-output");
        File sourceDir = new File(outputRoot, "WT");
        assertTrue(sourceDir.mkdirs());
        File source = new File(sourceDir, "Exp-A_LH_X.lif");
        assertTrue(source.createNewFile());
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        File projectJson = new File(settingsDir, ProjectFileIO.FILE_NAME);

        ProjectFile project = new ProjectFile();
        project.outputRoot = new File(temp.getRoot(), "old-output").getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = new File(temp.getRoot(), "old-output/WT/Exp-A_LH_X.lif").getAbsolutePath();
        item.extras.put(ProjectPathResolver.K_PATH_RELATIVE_TO_OUTPUT_ROOT, "WT/Exp-A_LH_X.lif");
        project.items.add(item);

        ProjectPathResolver.relocateForLoad(project, projectJson, outputRoot);

        assertEquals(outputRoot.getAbsolutePath(), project.outputRoot);
        assertEquals(source.getAbsolutePath(), project.items.get(0).path);
    }

    @Test
    public void relocateForLoad_anchorsRootToOpenedLocationWhenStoredRootStillExists() throws Exception {
        // Reproduces the reopen bug: a project folder is copied / restored to a
        // new location while the ORIGINAL still exists on disk. project.json
        // carries the original's absolute outputRoot. Reopening from the new
        // location must resolve the root to where project.json physically lives
        // now, so channel_config.json (which sits beside project.json) reloads
        // from the opened project, not the stale original.
        File originalRoot = temp.newFolder("original-project");
        // Make the stale path a believable FLASH project directory that exists.
        assertTrue(FlashProjectLayout.forDirectory(originalRoot.getAbsolutePath())
                .configurationWriteDir().mkdirs());

        File actualRoot = temp.newFolder("copied-project");
        File settingsDir = FlashProjectLayout.forDirectory(actualRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        File projectJson = new File(settingsDir, ProjectFileIO.FILE_NAME);

        ProjectFile project = new ProjectFile();
        project.outputRoot = originalRoot.getAbsolutePath();

        ProjectPathResolver.relocateForLoad(project, projectJson, actualRoot);

        assertEquals(actualRoot.getAbsolutePath(), project.outputRoot);
    }

    @Test
    public void relocateForLoad_mapsOldOutputRootPrefixToCurrentRoot() throws Exception {
        File oldOutputRoot = new File(temp.getRoot(), "old-output");
        File outputRoot = temp.newFolder("new-output");
        File sourceDir = new File(outputRoot, "KO");
        assertTrue(sourceDir.mkdirs());
        File source = new File(sourceDir, "Exp-B_RH_Y.tif");
        assertTrue(source.createNewFile());
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        File projectJson = new File(settingsDir, ProjectFileIO.FILE_NAME);

        ProjectFile project = new ProjectFile();
        project.outputRoot = oldOutputRoot.getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = new File(oldOutputRoot, "KO/Exp-B_RH_Y.tif").getAbsolutePath();
        project.items.add(item);

        ProjectPathResolver.relocateForLoad(project, projectJson, outputRoot);

        assertEquals(outputRoot.getAbsolutePath(), project.outputRoot);
        assertEquals(source.getAbsolutePath(), project.items.get(0).path);
    }

    @Test
    public void relocateForLoad_prefersCopiedSourceWhenOriginalStillExists() throws Exception {
        File originalRoot = temp.newFolder("original-with-source");
        File copiedRoot = temp.newFolder("copied-with-source");
        File originalSource = writeBytes(new File(originalRoot, "input/source.tif"), "ORIGINAL");
        File copiedSource = writeBytes(new File(copiedRoot, "input/source.tif"), "COPIED");
        File projectJson = createProjectJsonPlaceholder(copiedRoot);

        ProjectFile project = projectWithSource(originalRoot, originalSource, "input/source.tif");
        ProjectPathResolver.relocateForLoad(project, projectJson, copiedRoot, false);

        File resolved = new File(project.items.get(0).path);
        assertEquals(copiedSource.toPath().toRealPath(), resolved.toPath());
        assertArrayEquals("COPIED".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(resolved.toPath()));
        assertArrayEquals("ORIGINAL".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(originalSource.toPath()));
    }

    @Test
    public void relocateForLoad_anchorsSourcesToPhysicalProjectJsonInsteadOfFallback() throws Exception {
        File originalRoot = temp.newFolder("physical-anchor-original");
        File openedRoot = temp.newFolder("physical-anchor-opened");
        File misleadingFallback = temp.newFolder("physical-anchor-fallback");
        File originalSource = writeBytes(new File(originalRoot, "input/source.tif"), "ORIGINAL");
        File openedSource = writeBytes(new File(openedRoot, "input/source.tif"), "OPENED");
        writeBytes(new File(misleadingFallback, "input/source.tif"), "FALLBACK");
        File projectJson = createProjectJsonPlaceholder(openedRoot);
        ProjectFile project = projectWithSource(originalRoot, originalSource, "input/source.tif");

        ProjectPathResolver.relocateForLoad(project, projectJson, misleadingFallback, false);

        assertEquals(openedRoot.toPath().toRealPath(), new File(project.outputRoot).toPath());
        assertEquals(openedSource.toPath().toRealPath(), new File(project.items.get(0).path).toPath());
    }

    @Test
    public void imageSourceDispatcher_opensResolversCopiedAuthoritativePath() throws Exception {
        File originalRoot = temp.newFolder("dispatcher-original");
        File copiedRoot = temp.newFolder("dispatcher-copy");
        File originalSource = writeBytes(new File(originalRoot, "input/source.tif"), "OLD");
        File copiedSource = writeBytes(new File(copiedRoot, "input/source.tif"), "NEW");
        File settingsDir = FlashProjectLayout.forDirectory(copiedRoot.getAbsolutePath())
                .configurationWriteDir();
        ProjectFile project = projectWithSource(originalRoot, originalSource, "input/source.tif");
        ProjectFileIO.write(settingsDir, project);

        List<File> sources = ImageSourceDispatcher.projectTiffFiles(copiedRoot.getAbsolutePath());

        assertEquals(1, sources.size());
        assertEquals(copiedSource.toPath().toRealPath(), sources.get(0).toPath());
        assertArrayEquals("NEW".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(sources.get(0).toPath()));
    }

    @Test
    public void relocateForLoad_preservesExternalSourceWhenContainedHintIsMissing() throws Exception {
        File copiedRoot = temp.newFolder("copy-with-external-source");
        File externalSource = writeBytes(temp.newFile("external-source.tif"), "EXTERNAL");
        File projectJson = createProjectJsonPlaceholder(copiedRoot);
        ProjectFile project = projectWithSource(null, externalSource, "input/not-present.tif");

        ProjectPathResolver.relocateForLoad(project, projectJson, copiedRoot, false);

        assertEquals(externalSource.toPath().toRealPath(),
                new File(project.items.get(0).path).toPath());
        assertArrayEquals("EXTERNAL".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(new File(project.items.get(0).path).toPath()));
    }

    @Test
    public void relocateForLoad_rejectsParentTraversalEvenWhenOriginalExists() throws Exception {
        File root = temp.newFolder("traversal-project");
        File outside = writeBytes(temp.newFile("outside-traversal.tif"), "OUTSIDE");
        File projectJson = createProjectJsonPlaceholder(root);
        ProjectFile project = projectWithSource(null, outside, "../outside-traversal.tif");

        ProjectPathResolver.SourceResolutionException failure = expectResolutionFailure(
                project, projectJson, root);

        assertTrue(failure.getMessage().contains(".."));
        assertTrue(failure.getMessage().contains(root.getAbsolutePath()));
        assertTrue(failure.getMessage().contains(outside.getAbsolutePath()));
    }

    @Test
    public void relocateForLoad_rejectsMixedSeparatorTraversal() throws Exception {
        File root = temp.newFolder("mixed-separator-project");
        File outside = writeBytes(temp.newFile("outside-mixed.tif"), "OUTSIDE-MIXED");
        File projectJson = createProjectJsonPlaceholder(root);
        ProjectFile project = projectWithSource(null, outside,
                "inside\\../..\\outside-mixed.tif");

        ProjectPathResolver.SourceResolutionException failure = expectResolutionFailure(
                project, projectJson, root);

        assertTrue(failure.getMessage().contains("parent ('..')"));
        assertTrue(failure.getMessage().contains("inside\\../..\\outside-mixed.tif"));
    }

    @Test
    public void relocateForLoad_rejectsAbsoluteRelativeHint() throws Exception {
        File root = temp.newFolder("absolute-hint-project");
        File outside = writeBytes(temp.newFile("outside-absolute.tif"), "OUTSIDE-ABSOLUTE");
        File projectJson = createProjectJsonPlaceholder(root);
        ProjectFile project = projectWithSource(null, outside, outside.getAbsolutePath());

        ProjectPathResolver.SourceResolutionException failure = expectResolutionFailure(
                project, projectJson, root);

        assertTrue(failure.getMessage().contains("absolute paths are not allowed"));
        assertTrue(failure.getMessage().contains(outside.getAbsolutePath()));
    }

    @Test
    public void relocateForLoad_rejectsFileSymlinkEscape() throws Exception {
        File root = temp.newFolder("file-symlink-project");
        File outside = writeBytes(temp.newFile("outside-link.tif"), "OUTSIDE-LINK");
        Path link = new File(root, "inside-link.tif").toPath();
        createSymbolicLinkOrSkip(link, outside.toPath());
        File projectJson = createProjectJsonPlaceholder(root);
        ProjectFile project = projectWithSource(null, outside, "inside-link.tif");

        ProjectPathResolver.SourceResolutionException failure = expectResolutionFailure(
                project, projectJson, root);

        assertTrue(failure.getMessage().contains("symlink, junction, or reparse point"));
        assertTrue(failure.getMessage().contains(outside.toPath().toRealPath().toString()));
    }

    @Test
    public void relocateForLoad_rejectsDirectorySymlinkEscape() throws Exception {
        File root = temp.newFolder("directory-symlink-project");
        File outsideDir = temp.newFolder("outside-symlink-directory");
        File outside = writeBytes(new File(outsideDir, "outside-dir-link.tif"), "OUTSIDE-DIR-LINK");
        Path link = new File(root, "linked-directory").toPath();
        createSymbolicLinkOrSkip(link, outsideDir.toPath());
        File projectJson = createProjectJsonPlaceholder(root);
        ProjectFile project = projectWithSource(null, outside, "linked-directory/outside-dir-link.tif");

        ProjectPathResolver.SourceResolutionException failure = expectResolutionFailure(
                project, projectJson, root);

        assertTrue(failure.getMessage().contains("symlink, junction, or reparse point"));
        assertTrue(failure.getMessage().contains(outside.toPath().toRealPath().toString()));
    }

    @Test
    public void relocateForLoad_rejectsWindowsJunctionEscape() throws Exception {
        Assume.assumeTrue("Windows junction evidence is Windows-only", isWindows());
        File root = temp.newFolder("junction-project");
        File outsideDir = temp.newFolder("outside-junction-directory");
        File outside = writeBytes(new File(outsideDir, "outside-junction.tif"), "OUTSIDE-JUNCTION");
        File junction = new File(root, "junction");
        createJunctionOrSkip(junction, outsideDir);
        try {
            File projectJson = createProjectJsonPlaceholder(root);
            ProjectFile project = projectWithSource(null, outside, "junction/outside-junction.tif");

            ProjectPathResolver.SourceResolutionException failure = expectResolutionFailure(
                    project, projectJson, root);

            assertTrue(failure.getMessage().contains("symlink, junction, or reparse point"));
            assertTrue(failure.getMessage().contains(outside.toPath().toRealPath().toString()));
        } finally {
            Files.deleteIfExists(junction.toPath());
        }
    }

    @Test
    public void relocateForLoad_reportsDifferentContainedCandidatesAsAmbiguous() throws Exception {
        File originalRoot = temp.newFolder("ambiguous-original");
        File copiedRoot = temp.newFolder("ambiguous-copy");
        File originalSource = writeBytes(new File(originalRoot, "implicit/source.tif"), "ORIGINAL");
        File explicitCopy = writeBytes(new File(copiedRoot, "explicit/source.tif"), "EXPLICIT");
        File implicitCopy = writeBytes(new File(copiedRoot, "implicit/source.tif"), "IMPLICIT");
        File projectJson = createProjectJsonPlaceholder(copiedRoot);
        ProjectFile project = projectWithSource(originalRoot, originalSource, "explicit/source.tif");

        ProjectPathResolver.SourceResolutionException failure = expectResolutionFailure(
                project, projectJson, copiedRoot);

        assertTrue(failure.getMessage().contains("Ambiguous"));
        assertTrue(failure.getMessage().contains(explicitCopy.toPath().toRealPath().toString()));
        assertTrue(failure.getMessage().contains(implicitCopy.toPath().toRealPath().toString()));
    }

    private File createProjectJsonPlaceholder(File outputRoot) throws IOException {
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        File projectJson = new File(settingsDir, ProjectFileIO.FILE_NAME);
        assertTrue(projectJson.createNewFile());
        return projectJson;
    }

    private static ProjectFile projectWithSource(File storedOutputRoot, File storedSource,
                                                 String relativeHint) {
        ProjectFile project = new ProjectFile();
        project.outputRoot = storedOutputRoot == null ? null : storedOutputRoot.getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = storedSource.getAbsolutePath();
        if (relativeHint != null) {
            item.extras.put(ProjectPathResolver.K_PATH_RELATIVE_TO_OUTPUT_ROOT, relativeHint);
        }
        project.items.add(item);
        return project;
    }

    private static File writeBytes(File file, String sentinel) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            assertTrue(parent.mkdirs());
        }
        Files.write(file.toPath(), sentinel.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static ProjectPathResolver.SourceResolutionException expectResolutionFailure(
            ProjectFile project, File projectJson, File openedRoot) {
        try {
            ProjectPathResolver.relocateForLoad(project, projectJson, openedRoot, false);
            fail("Expected unsafe or ambiguous project source resolution to fail.");
            return null;
        } catch (ProjectPathResolver.SourceResolutionException expected) {
            return expected;
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException e) {
            Assume.assumeNoException("Symbolic links are not supported", e);
        } catch (SecurityException e) {
            Assume.assumeNoException("Symbolic link creation is not permitted", e);
        } catch (IOException e) {
            Assume.assumeNoException("Symbolic link creation is not permitted", e);
        }
    }

    private static void createJunctionOrSkip(File junction, File target) throws Exception {
        String command = "mklink /J \"" + junction.getAbsolutePath()
                + "\" \"" + target.getAbsolutePath() + "\"";
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", command)
                .redirectErrorStream(true).start();
        String output;
        try {
            output = readFully(process.getInputStream());
        } finally {
            process.getInputStream().close();
        }
        int exit = process.waitFor();
        Assume.assumeTrue("Could not create a real Windows junction (exit " + exit
                + "): " + output, exit == 0 && junction.isDirectory());
    }

    private static String readFully(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
