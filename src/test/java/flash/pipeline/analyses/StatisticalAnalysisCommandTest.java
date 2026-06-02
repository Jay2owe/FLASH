package flash.pipeline.analyses;

import flash.pipeline.analyses.wizard.StatisticsPreset;
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
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StatisticalAnalysisCommandTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void commandMetadataDeclaresHeadlessPluginAndCoreParameters() throws Exception {
        Plugin plugin = StatisticalAnalysisCommand.class.getAnnotation(Plugin.class);
        assertNotNull(plugin);
        assertEquals(Command.class, plugin.type());
        assertTrue(plugin.headless());
        assertTrue(!plugin.visible());

        assertParameter("directory", File.class);
        assertParameter("coordinator", AnalysisRunCoordinator.class);
        assertParameter("metricAggregation", String.class);
        assertParameter("sumMetrics", String.class);
        assertParameter("meanMetrics", String.class);
    }

    @Test
    public void commandResolveConfigParsesMetricAggregationParameters() throws Exception {
        StatisticalAnalysisCommand command = new StatisticalAnalysisCommand();
        setField(command, "metricAggregation", "ObjectsDetected:sum");
        setField(command, "sumMetrics", "SpotTotal");
        setField(command, "meanMetrics", "CellCount");

        Method resolveConfig = StatisticalAnalysisCommand.class
                .getDeclaredMethod("resolveConfig", StatisticsPreset.class);
        resolveConfig.setAccessible(true);
        StatisticsConfig cfg = (StatisticsConfig) resolveConfig.invoke(command, new Object[]{null});

        assertEquals(StatisticsConfig.MetricAggregation.SUM,
                cfg.metricAggregationFor("ObjectsDetected"));
        assertEquals(StatisticsConfig.MetricAggregation.SUM,
                cfg.metricAggregationFor("SpotTotal"));
        assertEquals(StatisticsConfig.MetricAggregation.MEAN,
                cfg.metricAggregationFor("cellcount"));
    }

    @Test
    public void coordinatorRunEmitsMasterCsvInputAndStatisticsOutputs() throws Exception {
        File project = temp.newFolder("statistics-runrecord");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        writeMasterObjects(layout.projectSummaryWriteFile(FlashProjectLayout.MASTER_OBJECTS_FILENAME));

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Ctl1", "Control");
        conditions.put("Ctl2", "Control");
        conditions.put("Ctl3", "Control");
        conditions.put("Tx1", "Treatment");
        conditions.put("Tx2", "Treatment");
        conditions.put("Tx3", "Treatment");
        ConditionManifestIO.saveAssignments(project.getAbsolutePath(), conditions);

        final StatisticalAnalysis analysis = new StatisticalAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);

        new AnalysisRunCoordinator().run(analysis, 9, "Statistical Analysis",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(project.getAbsolutePath());
                        return null;
                    }
                });

        RunRecord record = latestRecord(project);
        assertEquals("StatisticalAnalysis", record.analysis);
        assertEquals("ok", record.status);
        assertTrue(hasInputEnding(record, FlashProjectLayout.MASTER_OBJECTS_FILENAME));
        assertTrue(hasOutputEnding(record, FlashProjectLayout.STATISTICS_FILENAME));
        assertTrue(hasOutputEnding(record, StatisticalAnalysis.SUPERPLOT_FILENAME));
    }

    private static void assertParameter(String name, Class<?> type) throws Exception {
        Field field = StatisticalAnalysisCommand.class.getDeclaredField(name);
        assertEquals(type, field.getType());
        assertNotNull(field.getAnnotation(Parameter.class));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void writeMasterObjects(File file) throws Exception {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        PrintWriter pw = new PrintWriter(file);
        try {
            pw.println("AnimalName,DAPI_Count");
            pw.println("Ctl1,10");
            pw.println("Ctl2,11");
            pw.println("Ctl3,12");
            pw.println("Tx1,20");
            pw.println("Tx2,21");
            pw.println("Tx3,22");
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
