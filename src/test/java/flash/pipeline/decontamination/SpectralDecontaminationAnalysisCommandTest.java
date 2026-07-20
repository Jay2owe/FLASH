package flash.pipeline.decontamination;

import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpectralDecontaminationAnalysisCommandTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void commandMetadataDeclaresHeadlessHiddenCommandParameters() throws Exception {
        Plugin plugin = SpectralDecontaminationAnalysisCommand.class.getAnnotation(Plugin.class);
        assertNotNull(plugin);
        assertEquals(Command.class, plugin.type());
        assertTrue(plugin.headless());
        assertFalse(plugin.visible());
        assertParameter("directory");
        assertParameter("coordinator");
    }

    @Test
    public void coordinatorRunEmitsWarningRecordWithoutChannelConfig() throws Exception {
        final File project = temp.newFolder("spectral-runrecord");
        final SpectralDecontaminationAnalysis analysis = new SpectralDecontaminationAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);

        new AnalysisRunCoordinator().run(analysis, 11,
                "Spectral Decontamination (Experimental)",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(project.getAbsolutePath());
                        return null;
                    }
                });

        RunRecord record = latestRecord(project);
        assertEquals("SpectralDecontaminationAnalysis", record.analysis);
        assertEquals("warn", record.status);
        assertTrue(hasMessageContaining(record, "Set Up Configuration"));
        assertTrue(record.inputs.isEmpty());
        assertTrue(record.outputs.isEmpty());
    }

    private static void assertParameter(String fieldName) throws Exception {
        Field field = SpectralDecontaminationAnalysisCommand.class.getDeclaredField(fieldName);
        assertNotNull(field.getAnnotation(Parameter.class));
    }

    private static boolean hasMessageContaining(RunRecord record, String text) {
        for (RunRecord.Message message : record.messages) {
            if (message.text != null && message.text.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static RunRecord latestRecord(File project) {
        File runsDir = FlashProjectLayout.forDirectory(project.getAbsolutePath()).runJsonlWriteDir();
        File[] files = runsDir.listFiles();
        assertNotNull("runs dir should exist", files);
        File latest = null;
        for (File f : files) {
            if (f.getName().endsWith(RunRecordIO.EXTENSION)) {
                latest = f;
            }
        }
        assertNotNull("run record should exist", latest);
        return RunRecordIO.readLatest(latest);
    }
}
