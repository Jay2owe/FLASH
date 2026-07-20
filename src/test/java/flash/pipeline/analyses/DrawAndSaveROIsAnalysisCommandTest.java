package flash.pipeline.analyses;

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DrawAndSaveROIsAnalysisCommandTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void commandClassDeclaresSciJavaMetadata() throws Exception {
        Class<?> commandClass = Class.forName(
                "flash.pipeline.analyses.DrawAndSaveROIsAnalysisCommand");

        Plugin plugin = commandClass.getAnnotation(Plugin.class);
        assertNotNull("command should be a SciJava plugin", plugin);
        assertEquals(Command.class, plugin.type());
        assertParameter(commandClass, "directory");
        assertParameter(commandClass, "coordinator");
    }

    @Test
    public void coordinatorRunEmitsHeadedOnlyWarningRecord() throws Exception {
        File project = temp.newFolder("draw-rois-runrecord");
        final DrawAndSaveROIsAnalysis analysis = new DrawAndSaveROIsAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setCommandMode(true);

        new AnalysisRunCoordinator().run(analysis, 1, "Draw ROIs and Orientate Images",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(project.getAbsolutePath());
                        return null;
                    }
                });

        RunRecord record = latestRecord(project);
        assertEquals("DrawAndSaveROIsAnalysis", record.analysis);
        assertEquals(1, record.analysisIndex);
        assertEquals("warn", record.status);
        assertTrue(hasMessageContaining(record, "headed-only"));
    }

    private static void assertParameter(Class<?> commandClass, String fieldName) throws Exception {
        Field field = commandClass.getDeclaredField(fieldName);
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
