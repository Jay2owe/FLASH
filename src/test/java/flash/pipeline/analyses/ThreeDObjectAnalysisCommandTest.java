package flash.pipeline.analyses;

import flash.pipeline.analyses.wizard.ThreeDObjectPreset;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ThreeDObjectAnalysisCommandTest {

    @Test
    public void presetJsonOipOverridePreservesCompleteChannelBindings() throws Exception {
        ThreeDObjectPreset source = boundPreset();
        ThreeDObjectAnalysisCommand command = new ThreeDObjectAnalysisCommand();
        setField(command, "presetJson", source.toJson());
        setField(command, "doRadialProfile", Boolean.FALSE);
        setField(command, "oipRadialBins", Integer.valueOf(37));

        ThreeDObjectPreset resolved = resolvePreset(command);
        ThreeDObjectPreset overridden = withOipOverrides(command, resolved);

        assertFalse(overridden.isDoRadialProfile());
        assertEquals(37, overridden.getOipRadialBins());
        assertEquals(source.getChannelSettings().keySet(),
                overridden.getChannelSettings().keySet());
        ThreeDObjectPreset.ChannelSetting nuclei =
                overridden.getChannelSettings().get("marker:nuclei_dapi");
        ThreeDObjectPreset.ChannelSetting microglia =
                overridden.getChannelSettings().get("marker:microglia_iba1");
        assertEquals(11.25, nuclei.getColocThresholdPercent(), 0.0);
        assertEquals(42.75, microglia.getColocThresholdPercent(), 0.0);
        assertTrue(nuclei.isNuclearMarker());
        assertTrue(nuclei.isOverlapMarker());
        assertTrue(microglia.isProcessChannel());
        assertTrue(microglia.isOverlapTarget());
        assertTrue(overridden.toJsonObject().containsKey("channelSettings"));
        assertFalse(overridden.toJsonObject().containsKey("channelDefaults"));
    }

    @Test
    public void noOipOverrideReturnsResolvedPresetWithoutCopying() throws Exception {
        ThreeDObjectPreset source = boundPreset();
        ThreeDObjectAnalysisCommand command = new ThreeDObjectAnalysisCommand();

        assertSame(source, withOipOverrides(command, source));
    }

    private static ThreeDObjectPreset resolvePreset(
            ThreeDObjectAnalysisCommand command) throws Exception {
        Method method = ThreeDObjectAnalysisCommand.class.getDeclaredMethod(
                "resolvePreset", File.class);
        method.setAccessible(true);
        return (ThreeDObjectPreset) method.invoke(command, new File("."));
    }

    private static ThreeDObjectPreset withOipOverrides(
            ThreeDObjectAnalysisCommand command,
            ThreeDObjectPreset preset) throws Exception {
        Method method = ThreeDObjectAnalysisCommand.class.getDeclaredMethod(
                "withOipOverrides", ThreeDObjectPreset.class);
        method.setAccessible(true);
        return (ThreeDObjectPreset) method.invoke(command, preset);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ThreeDObjectPreset boundPreset() {
        Map<String, ThreeDObjectPreset.ChannelSetting> channels =
                new LinkedHashMap<String, ThreeDObjectPreset.ChannelSetting>();
        ThreeDObjectPreset.ChannelSetting nuclei = new ThreeDObjectPreset.ChannelSetting(
                "DAPI", "nuclei_dapi", 11.25, 21.5,
                false, true, true, false);
        ThreeDObjectPreset.ChannelSetting microglia = new ThreeDObjectPreset.ChannelSetting(
                "IBA1", "microglia_iba1", 42.75, 52.125,
                true, false, false, true);
        channels.put(nuclei.getIdentityKey(), nuclei);
        channels.put(microglia.getIdentityKey(), microglia);
        return new ThreeDObjectPreset(
                "Command schema 2", null, ThreeDObjectPreset.CURRENT_LIBRARY_VERSION,
                true, true, true, true, false, true,
                true, false, true, channels,
                true, true, true, false, false, false, true,
                "whole_box", "per_object_minmax", 20, 12, 3, 50, 0.0, 50.0);
    }
}
