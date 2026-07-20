package flash.pipeline.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExcelRuntimeCheckerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void checkReportsMissingCoreExcelJars() throws Exception {
        File fijiDir = temp.newFolder("excel-runtime-missing");
        new File(fijiDir, "jars").mkdirs();
        new File(fijiDir, "plugins").mkdirs();

        List<String> issues = ExcelRuntimeChecker.check(fijiDir);

        assertFalse(issues.isEmpty());
        assertTrue(containsIssue(issues, "Apache POI core: MISSING"));
        assertTrue(containsIssue(issues, "curvesapi: MISSING"));
    }

    @Test
    public void checkAcceptsCompatibleExistingOptionalSupportJars() throws Exception {
        File fijiDir = temp.newFolder("excel-runtime-compatible");
        File jars = new File(fijiDir, "jars");
        jars.mkdirs();
        new File(fijiDir, "plugins").mkdirs();

        touch(jars, "poi-3.17.jar");
        touch(jars, "poi-ooxml-3.17.jar");
        touch(jars, "poi-ooxml-schemas-3.17.jar");
        touch(jars, "curvesapi-1.04.jar");

        // These are accepted if any version is already present.
        touch(jars, "xmlbeans-2.6.0.jar");
        touch(jars, "commons-collections4-4.2.jar");
        touch(jars, "commons-codec-1.17.1.jar");

        List<String> issues = ExcelRuntimeChecker.check(fijiDir);

        assertTrue(issues.isEmpty());
    }

    @Test
    public void checkReportsWrongCorePoiVersion() throws Exception {
        File fijiDir = temp.newFolder("excel-runtime-wrong-version");
        File jars = new File(fijiDir, "jars");
        jars.mkdirs();
        new File(fijiDir, "plugins").mkdirs();

        touch(jars, "poi-4.1.2.jar");
        touch(jars, "poi-ooxml-3.17.jar");
        touch(jars, "poi-ooxml-schemas-3.17.jar");
        touch(jars, "xmlbeans-3.1.0.jar");
        touch(jars, "curvesapi-1.04.jar");
        touch(jars, "commons-collections4-4.1.jar");
        touch(jars, "commons-codec-1.10.jar");

        List<String> issues = ExcelRuntimeChecker.check(fijiDir);

        assertTrue(containsIssue(issues, "Apache POI core: found poi-4.1.2.jar, need poi-3.17.jar"));
    }

    @Test
    public void registryBackedSizeConstantIsUsedForExcelFixLabel() {
        DependencySpec spec = DependencyRegistry.get(DependencyId.APACHE_POI_RUNTIME);

        assertEquals(DependencyRegistry.APACHE_POI_RUNTIME_BYTES, spec.getApproxDownloadSizeBytes());
        assertEquals("(~13.2 MB)", DependencyRegistry.formatApproxSize(spec.getApproxDownloadSizeBytes()));
        assertTrue(spec.formatButtonLabel(null).contains("(~13.2 MB)"));
    }

    private static void touch(File dir, String name) throws Exception {
        File file = new File(dir, name);
        assertTrue(file.createNewFile());
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
