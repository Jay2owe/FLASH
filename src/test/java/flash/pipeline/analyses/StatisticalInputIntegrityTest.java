package flash.pipeline.analyses;

import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class StatisticalInputIntegrityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void malformedTailCannotPublishStatisticsFromOnlyFirstRows() throws Exception {
        rejectsMaster("AnimalName,Value\nControl1,10\nTreatment1,20\n\"unterminated,30\n");
    }

    @Test public void duplicateObservationCannotSilentlyReplacePreviousValue() throws Exception {
        rejectsMaster("AnimalName,Value\nControl1,10\nControl1,1000\nTreatment1,20\n");
    }

    @Test public void missingRequestedPresetCannotSilentlySelectDefaultTests() throws Exception {
        File root = temp.newFolder();
        try {
            new StatisticalAnalysis().setCliConfig(CLIArgumentParser.parse(
                    "dir=[" + root.getAbsolutePath() + "] stats.preset=[absent-user-preset]"));
            fail("A missing requested preset must fail before analysis");
        } catch (UncheckedIOException expected) {
            assertTrue(expected.getMessage().contains("absent-user-preset"));
        }
    }

    private void rejectsMaster(String content) throws Exception {
        File root = temp.newFolder();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getPath());
        File dir = layout.tablesProjectSummaryWriteDir();
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, FlashProjectLayout.MASTER_OBJECTS_FILENAME).toPath(),
                content.getBytes(StandardCharsets.UTF_8));
        File accepted = new File(dir, "Statistics.csv");
        byte[] previous = "previous accepted statistics\n".getBytes(StandardCharsets.UTF_8);
        Files.write(accepted.toPath(), previous);
        StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setSuppressDialogs(true);
        try {
            analysis.execute(root.getPath());
            fail("An invalid input table must not produce statistics");
        } catch (UncheckedIOException expected) {
            assertTrue(expected.getMessage().contains("incomplete master table"));
        }
        assertArrayEquals(previous, Files.readAllBytes(accepted.toPath()));
    }
}
