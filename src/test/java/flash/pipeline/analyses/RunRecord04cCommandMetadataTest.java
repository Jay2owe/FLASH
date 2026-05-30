package flash.pipeline.analyses;

import flash.pipeline.execution.AnalysisRunCoordinator;

import org.junit.Test;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RunRecord04cCommandMetadataTest {

    @Test
    public void threeDObjectCommandDeclaresHeadlessPluginAndParameters() throws Exception {
        assertCommandMetadata(ThreeDObjectAnalysisCommand.class);
        assertParameter(ThreeDObjectAnalysisCommand.class, "directory", File.class);
        assertParameter(ThreeDObjectAnalysisCommand.class, "coordinator", AnalysisRunCoordinator.class);
        assertParameter(ThreeDObjectAnalysisCommand.class, "presetJson", String.class);
    }

    @Test
    public void spatialCommandDeclaresHeadlessPluginAndParameters() throws Exception {
        assertCommandMetadata(SpatialAnalysisCommand.class);
        assertParameter(SpatialAnalysisCommand.class, "directory", File.class);
        assertParameter(SpatialAnalysisCommand.class, "coordinator", AnalysisRunCoordinator.class);
        assertParameter(SpatialAnalysisCommand.class, "presetJson", String.class);
    }

    @Test
    public void lineDistanceCommandDeclaresHeadlessPluginAndParameters() throws Exception {
        assertCommandMetadata(LineDistanceAnalysisCommand.class);
        assertParameter(LineDistanceAnalysisCommand.class, "directory", File.class);
        assertParameter(LineDistanceAnalysisCommand.class, "coordinator", AnalysisRunCoordinator.class);
        assertParameter(LineDistanceAnalysisCommand.class, "lineSets", String.class);
    }

    @Test
    public void intensityCommandDeclaresHeadlessPluginAndParameters() throws Exception {
        assertCommandMetadata(IntensityAnalysisV2Command.class);
        assertParameter(IntensityAnalysisV2Command.class, "directory", File.class);
        assertParameter(IntensityAnalysisV2Command.class, "coordinator", AnalysisRunCoordinator.class);
        assertParameter(IntensityAnalysisV2Command.class, "presetJson", String.class);
    }

    private static void assertCommandMetadata(Class<?> commandClass) {
        Plugin plugin = commandClass.getAnnotation(Plugin.class);
        assertNotNull(plugin);
        assertEquals(Command.class, plugin.type());
        assertTrue(plugin.headless());
        assertTrue(!plugin.visible());
    }

    private static void assertParameter(Class<?> commandClass, String name, Class<?> type) throws Exception {
        Field field = commandClass.getDeclaredField(name);
        assertEquals(type, field.getType());
        assertNotNull(field.getAnnotation(Parameter.class));
    }
}
