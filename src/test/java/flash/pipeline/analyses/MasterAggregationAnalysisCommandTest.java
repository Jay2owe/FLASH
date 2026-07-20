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
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MasterAggregationAnalysisCommandTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void commandMetadataDeclaresHeadlessPluginAndCoreParameters() throws Exception {
        Plugin plugin = MasterAggregationAnalysisCommand.class.getAnnotation(Plugin.class);
        assertNotNull(plugin);
        assertEquals(Command.class, plugin.type());
        assertTrue(plugin.headless());
        assertTrue(!plugin.visible());

        assertParameter("directory", File.class);
        assertParameter("coordinator", AnalysisRunCoordinator.class);
    }

    @Test
    public void coordinatorRunEmitsInputsAndMasterCsvOutputs() throws Exception {
        File project = temp.newFolder("aggregation-runrecord");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        writeCsv(new File(layout.tablesObjectsWriteDir(), "DAPI.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM",
                "A1,R1,SCN,S1,LH,1,2,3,4,5,6");
        writeCsv(new File(layout.tablesIntensityWriteDir(), "DAPI.csv"),
                "Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area",
                "A1,R1,SCN,S1,LH,10,20");

        final MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);

        new AnalysisRunCoordinator().run(analysis, 8, "Combine results per condition / animal",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(project.getAbsolutePath());
                        return null;
                    }
                });

        RunRecord record = latestRecord(project);
        assertEquals("MasterAggregationAnalysis", record.analysis);
        assertEquals("warn", record.status);
        assertTrue(hasInputEnding(record, "Objects" + File.separator + "DAPI.csv"));
        assertTrue(hasInputEnding(record, "Intensity" + File.separator + "DAPI.csv"));
        assertTrue(hasOutputEnding(record, FlashProjectLayout.MASTER_OBJECTS_FILENAME));
        assertTrue(hasOutputEnding(record, FlashProjectLayout.MASTER_INTENSITIES_FILENAME));
    }

    private static void assertParameter(String name, Class<?> type) throws Exception {
        Field field = MasterAggregationAnalysisCommand.class.getDeclaredField(name);
        assertEquals(type, field.getType());
        assertNotNull(field.getAnnotation(Parameter.class));
    }

    private static void writeCsv(File file, String header, String row) throws Exception {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        PrintWriter pw = new PrintWriter(file);
        try {
            pw.println(header);
            pw.println(row);
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
