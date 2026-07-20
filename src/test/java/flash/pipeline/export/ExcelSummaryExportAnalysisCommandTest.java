package flash.pipeline.export;

import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.io.ConditionManifestIO;
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
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExcelSummaryExportAnalysisCommandTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void commandMetadataDeclaresHeadlessPluginAndCoreParameters() throws Exception {
        Plugin plugin = ExcelSummaryExportAnalysisCommand.class.getAnnotation(Plugin.class);
        assertNotNull(plugin);
        assertEquals(Command.class, plugin.type());
        assertTrue(plugin.headless());
        assertTrue(!plugin.visible());

        assertParameter("directory", File.class);
        assertParameter("coordinator", AnalysisRunCoordinator.class);
    }

    @Test
    public void coordinatorRunEmitsMasterCsvInputAndWorkbookOutput() throws Exception {
        File project = temp.newFolder("excel-runrecord");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        writeMasterObjects(layout.projectSummaryWriteFile(FlashProjectLayout.MASTER_OBJECTS_FILENAME));

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Ctl1", "Control");
        conditions.put("Tx1", "Treatment");
        ConditionManifestIO.saveAssignments(project.getAbsolutePath(), conditions);

        final ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setPreset(ExcelExportPreset.exploratoryDefault());

        new AnalysisRunCoordinator().run(analysis, 10, "Excel Summary Export",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(project.getAbsolutePath());
                        return null;
                    }
                });

        RunRecord record = latestRecord(project);
        assertEquals("ExcelSummaryExportAnalysis", record.analysis);
        assertEquals("ok", record.status);
        assertTrue(hasInputEnding(record, FlashProjectLayout.MASTER_OBJECTS_FILENAME));
        assertTrue(hasOutputEnding(record, FlashProjectLayout.SUMMARY_WORKBOOK_FILENAME));
    }

    private static void assertParameter(String name, Class<?> type) throws Exception {
        Field field = ExcelSummaryExportAnalysisCommand.class.getDeclaredField(name);
        assertEquals(type, field.getType());
        assertNotNull(field.getAnnotation(Parameter.class));
    }

    private static void writeMasterObjects(File file) throws Exception {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        PrintWriter pw = new PrintWriter(file);
        try {
            pw.println("AnimalName,DAPI_Count");
            pw.println("Ctl1,10");
            pw.println("Tx1,20");
        } finally {
            pw.close();
        }
    }

    private static boolean hasInputEnding(RunRecord record, String suffix) {
        for (RunRecord.InputItem input : record.inputs) {
            if (input.path != null && input.path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOutputEnding(RunRecord record, String suffix) {
        for (RunRecord.OutputItem output : record.outputs) {
            if (output.path != null && output.path.endsWith(suffix)) {
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
