package flash.pipeline.analyses;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.CancelConfirmationDialog;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CreateBinFileAnalysisIncrementalWriteTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stepOneCompletionWritesJsonWithChannelNamesConfigured() throws Exception {
        File binFolder = temp.newFolder("step-one");
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();

        invokePersistIncremental(analysis, binFolder, twoChannelConfig(), null,
                1, "Channel Identity", -1, null);

        ChannelConfig cfg = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertNotNull(cfg);
        assertEquals("DAPI", cfg.channels.get(0).name);
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                cfg.channels.get(0).statusOf(ChannelConfig.P_NAME));
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                cfg.channels.get(1).statusOf(ChannelConfig.P_COLOR));
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                cfg.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(1, ((Number) cfg.extras.get("lastStepIndex")).intValue());
    }

    @Test
    public void step5ThresholdCommitUpdatesThresholdStatusToConfigured() throws Exception {
        File binFolder = temp.newFolder("threshold");
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig user = twoChannelConfig();

        invokePersistIncremental(analysis, binFolder, user, null,
                1, "Channel Identity", -1, null);
        user.objectThresholds.set(0, "123");
        user.intensityThresholds.set(0, "123");
        invokePersistIncremental(analysis, binFolder, user, null,
                5, "Quality Check", 0, ChannelConfig.P_THRESHOLD);

        ChannelConfig cfg = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals("123", cfg.channels.get(0).threshold);
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                cfg.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                cfg.channels.get(0).statusOf(ChannelConfig.P_INTENSITY));
    }

    @Test
    public void step6FinalizationMarksAllPropertiesCommitted() throws Exception {
        File binFolder = temp.newFolder("commit");
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();

        invokePersistCommit(analysis, binFolder, twoChannelConfig(), new boolean[][]{{true, false}});

        ChannelConfig cfg = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        for (ChannelConfig.Channel channel : cfg.channels) {
            for (String key : propertyKeys()) {
                assertEquals(ChannelConfig.PropertyStatus.COMMITTED, channel.statusOf(key));
            }
        }
        assertEquals(6, ((Number) cfg.extras.get("lastStepIndex")).intValue());
    }

    @Test
    public void cancelSaveAndExitLeavesJsonInLatestState() throws Exception {
        File binFolder = temp.newFolder("cancel-save");
        CreateBinFileAnalysis.BinUserConfig user = twoChannelConfig();
        ChannelConfig seeded = ChannelConfigIO.fromBinUserConfig(user);
        seeded.extras.put("lastStepIndex", Integer.valueOf(5));
        seeded.channels.get(0).status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.CONFIGURED);
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder), seeded);
        CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.SAVE_AND_EXIT);

        assertTrue(invokeHandleCancelRequest(analysis, binFolder, user, null, 5, "Quality Check"));

        ChannelConfig cfg = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals(5, ((Number) cfg.extras.get("lastStepIndex")).intValue());
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                cfg.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
    }

    @Test
    public void cancelDiscardAndExitDeletesJson() throws Exception {
        File binFolder = temp.newFolder("cancel-discard");
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder),
                ChannelConfigIO.fromBinUserConfig(twoChannelConfig()));
        CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.DISCARD_AND_EXIT);

        assertTrue(invokeHandleCancelRequest(analysis, binFolder, twoChannelConfig(), null, 4, "Z-slice QC"));

        assertFalse(ChannelConfigIO.exists(FlashProjectLayout.settingsDir(binFolder)));
    }

    @Test
    public void resumeFromPartialJsonRestoresCfgAndStepIndex() throws Exception {
        File binFolder = temp.newFolder("resume");
        ChannelConfig cfg = ChannelConfigIO.fromBinUserConfig(twoChannelConfig());
        cfg.channels.get(1).status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.PENDING);
        cfg.extras.put("lastStepIndex", Integer.valueOf(5));
        cfg.extras.put("lastStepLabel", "Quality Check");
        cfg.extras.put("customSettings", Arrays.<Object>asList(
                Arrays.<Object>asList(Boolean.TRUE, Boolean.FALSE),
                Arrays.<Object>asList(Boolean.FALSE, Boolean.TRUE)));
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder), cfg);

        CreateBinFileAnalysis.WizardResumeState state =
                new CreateBinFileAnalysis().readWizardResumeState(binFolder);

        assertNotNull(state);
        assertEquals(5, state.stepIndex);
        assertEquals("Quality Check", state.stepLabel);
        assertEquals("IBA1", state.cfg.names.get(1));
        assertArrayEquals(new boolean[]{false, true}, state.customSettings[1]);
    }

    @Test
    public void incrementalWriteFailureDoesNotAbortWizard() throws Exception {
        File binFolder = temp.newFile("not-a-directory");

        invokePersistIncremental(new CreateBinFileAnalysis(), binFolder, twoChannelConfig(), null,
                1, "Channel Identity", -1, null);

        assertFalse(ChannelConfigIO.exists(FlashProjectLayout.settingsDir(binFolder)));
    }

    @Test
    public void extrasArePreservedAcrossIncrementalWrites() throws Exception {
        File binFolder = temp.newFolder("extras");
        ChannelConfig seeded = ChannelConfigIO.fromBinUserConfig(twoChannelConfig());
        seeded.extras.put("futureRootKey", "keep");
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder), seeded);

        invokePersistIncremental(new CreateBinFileAnalysis(), binFolder, twoChannelConfig(), null,
                2, "Analysis Scope", -1, null);

        ChannelConfig cfg = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals("keep", cfg.extras.get("futureRootKey"));
        assertEquals(2, ((Number) cfg.extras.get("lastStepIndex")).intValue());
    }

    private static void invokePersistIncremental(CreateBinFileAnalysis analysis, File binFolder,
                                                 CreateBinFileAnalysis.BinUserConfig user,
                                                 boolean[][] customSettings,
                                                 int stepIndex, String stepLabel,
                                                 int channelIndex, String propertyKey) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "persistIncremental",
                File.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                boolean[][].class,
                int.class,
                String.class,
                int.class,
                String.class);
        method.setAccessible(true);
        method.invoke(analysis, binFolder, user, customSettings,
                Integer.valueOf(stepIndex), stepLabel, Integer.valueOf(channelIndex), propertyKey);
    }

    private static void invokePersistCommit(CreateBinFileAnalysis analysis, File binFolder,
                                            CreateBinFileAnalysis.BinUserConfig user,
                                            boolean[][] customSettings) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "persistCommit",
                File.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                boolean[][].class);
        method.setAccessible(true);
        method.invoke(analysis, binFolder, user, customSettings);
    }

    private static boolean invokeHandleCancelRequest(CreateBinFileAnalysis analysis,
                                                     File binFolder,
                                                     CreateBinFileAnalysis.BinUserConfig cfg,
                                                     boolean[][] customSettings,
                                                     int step,
                                                     String label) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "handleCancelRequest",
                File.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                boolean[][].class,
                int.class,
                String.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(
                analysis,
                binFolder,
                cfg,
                customSettings,
                Integer.valueOf(step),
                label)).booleanValue();
    }

    private static CreateBinFileAnalysis.BinUserConfig twoChannelConfig() {
        CreateBinFileAnalysis.BinUserConfig cfg = new CreateBinFileAnalysis.BinUserConfig(
                new ArrayList<String>(Arrays.asList("DAPI", "IBA1")),
                new ArrayList<String>(Arrays.asList("Blue", "Green")),
                new ArrayList<String>(Arrays.asList("default", "220")),
                new ArrayList<String>(Arrays.asList("100-Infinity", "20-300")),
                new ArrayList<String>(Arrays.asList("None", "10-200")),
                new ArrayList<String>(Arrays.asList("Default", "Custom")),
                new ArrayList<String>(Arrays.asList("default", "220")));
        cfg.segmentationMethods.clear();
        cfg.segmentationMethods.addAll(Arrays.asList("classical", "stardist:0.5:0.4"));
        return cfg;
    }

    private static List<String> propertyKeys() {
        return Arrays.asList(
                ChannelConfig.P_NAME,
                ChannelConfig.P_COLOR,
                ChannelConfig.P_MARKER,
                ChannelConfig.P_THRESHOLD,
                ChannelConfig.P_SIZE,
                ChannelConfig.P_MINMAX,
                ChannelConfig.P_INTENSITY,
                ChannelConfig.P_SEGMENTATION,
                ChannelConfig.P_FILTER);
    }

    private static final class CancelChoiceAnalysis extends CreateBinFileAnalysis {
        private final CancelConfirmationDialog.Choice choice;

        CancelChoiceAnalysis(CancelConfirmationDialog.Choice choice) {
            this.choice = choice;
        }

        @Override
        protected CancelConfirmationDialog.Choice showCancelConfirmation(
                java.awt.Window owner,
                String stepLabel,
                List<String> progressLines,
                String draftPath) {
            return choice;
        }
    }
}
