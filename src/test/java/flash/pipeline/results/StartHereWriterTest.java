package flash.pipeline.results;

import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class StartHereWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void write_mentionsTopLevelResultsFoldersAndOptionalLinks() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        assertTrue(layout.summaryWorkbookWriteFile().getParentFile().mkdirs());
        assertTrue(layout.summaryWorkbookWriteFile().createNewFile());
        assertTrue(layout.qcReportWriteFile().getParentFile().mkdirs());
        assertTrue(layout.qcReportWriteFile().createNewFile());

        File out = StartHereWriter.write(layout);

        assertTrue(out.isFile());
        String html = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("Tables"));
        assertTrue(html.contains("Presentation Images"));
        assertTrue(html.contains("Analysis Images"));
        assertTrue(html.contains("QC"));
        assertTrue(html.contains("Run Records"));
        assertTrue(html.contains("Summary.xlsx"));
        assertTrue(html.contains("QC/QC_Report.html"));
    }
}
