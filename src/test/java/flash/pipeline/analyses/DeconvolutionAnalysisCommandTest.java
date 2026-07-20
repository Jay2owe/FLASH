package flash.pipeline.analyses;

import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.wizard.DeconvPreset;
import flash.pipeline.deconv.wizard.DeconvPresetIO;
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
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DeconvolutionAnalysisCommandTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void commandMetadataDeclaresHeadlessHiddenCommandParameters() throws Exception {
        Plugin plugin = DeconvolutionAnalysisCommand.class.getAnnotation(Plugin.class);
        assertNotNull(plugin);
        assertEquals(Command.class, plugin.type());
        assertTrue(plugin.headless());
        assertFalse(plugin.visible());
        assertParameter("directory");
        assertParameter("coordinator");
    }

    @Test
    public void coordinatorRunEmitsWarningRecordWithoutImageInputs() throws Exception {
        final File project = temp.newFolder("deconv-runrecord");
        final DeconvolutionAnalysis analysis = new DeconvolutionAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);

        new AnalysisRunCoordinator().run(analysis, 2, "3D Deconvolution",
                project.getAbsolutePath(), null, null, "", new Callable<Void>() {
                    @Override public Void call() {
                        analysis.execute(project.getAbsolutePath());
                        return null;
                    }
                });

        RunRecord record = latestRecord(project);
        assertEquals("DeconvolutionAnalysis", record.analysis);
        assertEquals("warn", record.status);
        assertTrue("warning message should explain why no run happened", !record.messages.isEmpty());
        assertTrue(record.inputs.isEmpty());
        assertTrue(record.outputs.isEmpty());
    }

    @Test
    public void commandExplicitOptionsOverrideNamedPresetDefaults() throws Exception {
        File project = temp.newFolder("deconv-command-named-preset");
        DeconvPreset preset = preset("Command Named Preset", 12, 0.01);
        new DeconvPresetIO(project).save(preset);

        DeconvolutionAnalysisCommand command = new DeconvolutionAnalysisCommand();
        setField(command, "deconvPreset", "Command Named Preset");
        setField(command, "engine", "DL2");
        setField(command, "psfModel", "Dougherty");
        setField(command, "iterations", Integer.valueOf(31));
        setField(command, "regularization", Double.valueOf(0.045));
        setField(command, "scopeModality", "spinningDisk");
        setField(command, "pinholeAU", Double.valueOf(1.25));
        setField(command, "sampleRI", Double.valueOf(1.46));

        CLIConfig parsed = parseCliConfig(command, project, preset);

        assertEquals("Command Named Preset", parsed.getDeconv().getPresetName());
        assertEquals("DL2", parsed.getDeconv().getEngine());
        assertEquals(Algorithm.RL_TV, parsed.getDeconv().getAlgorithm());
        assertEquals(PsfModel.DOUGHERTY_THEORETICAL, parsed.getDeconv().getPsfModel());
        assertEquals(31, parsed.getDeconv().getIterations());
        assertEquals(0.045, parsed.getDeconv().getRegularization(), 1e-12);
        assertEquals(ScopeModality.SPINNING_DISK, parsed.getDeconv().getScopeModality());

        Map<String, Object> parameters = commandParameters(command, preset, parsed);
        assertEquals("DL2", parameters.get("engineKey"));
        assertEquals("DOUGHERTY_THEORETICAL", parameters.get("psfModel"));
        assertEquals("SPINNING_DISK", parameters.get("scopeModality"));
        assertEquals(Integer.valueOf(31), parameters.get("iterations"));
        assertEquals(0.045, ((Number) parameters.get("regularization")).doubleValue(), 1e-12);
        assertEquals(1.25, ((Number) parameters.get("pinholeAU")).doubleValue(), 1e-12);
        assertEquals(1.46, ((Number) parameters.get("sampleRI")).doubleValue(), 1e-12);
    }

    @Test
    public void commandExplicitOptionsOverrideJsonPresetDefaults() throws Exception {
        File project = temp.newFolder("deconv-command-json-preset");
        DeconvPreset preset = preset("Command Json Preset", 14, 0.02);

        DeconvolutionAnalysisCommand command = new DeconvolutionAnalysisCommand();
        setField(command, "presetJson", preset.toJson());
        setField(command, "iterations", Integer.valueOf(29));

        CLIConfig parsed = parseCliConfig(command, project, preset);

        assertEquals("CLIJ2", parsed.getDeconv().getEngine());
        assertEquals(Algorithm.RL_TV, parsed.getDeconv().getAlgorithm());
        assertEquals(29, parsed.getDeconv().getIterations());
        assertEquals(0.02, parsed.getDeconv().getRegularization(), 1e-12);

        Map<String, Object> parameters = commandParameters(command, preset, parsed);
        assertEquals(Integer.valueOf(29), parameters.get("iterations"));
        assertEquals(0.02, ((Number) parameters.get("regularization")).doubleValue(), 1e-12);
    }

    private static void assertParameter(String fieldName) throws Exception {
        Field field = DeconvolutionAnalysisCommand.class.getDeclaredField(fieldName);
        assertNotNull(field.getAnnotation(Parameter.class));
    }

    private static CLIConfig parseCliConfig(DeconvolutionAnalysisCommand command,
                                            File project,
                                            DeconvPreset preset) throws Exception {
        Method method = DeconvolutionAnalysisCommand.class.getDeclaredMethod(
                "parseCliConfig", File.class, DeconvPreset.class);
        method.setAccessible(true);
        return (CLIConfig) method.invoke(command, project, preset);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> commandParameters(DeconvolutionAnalysisCommand command,
                                                         DeconvPreset preset,
                                                         CLIConfig cliConfig) throws Exception {
        Method method = DeconvolutionAnalysisCommand.class.getDeclaredMethod(
                "commandParameters", DeconvPreset.class, CLIConfig.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(command, preset, cliConfig);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static DeconvPreset preset(String name, int iterations, double regularization) {
        return new DeconvPreset(
                name,
                null,
                "CLIJ2",
                Algorithm.RL_TV,
                PsfModel.BORN_WOLF,
                iterations,
                regularization,
                ScopeModality.CONFOCAL,
                Double.valueOf(1.0),
                Double.valueOf(1.40));
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
