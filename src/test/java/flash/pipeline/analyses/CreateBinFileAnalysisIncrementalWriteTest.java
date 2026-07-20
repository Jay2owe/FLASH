package flash.pipeline.analyses;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.CancelConfirmationDialog;
import flash.pipeline.ui.CustomFilterEntryDialog;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
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
import static org.junit.Assert.assertNull;
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
    public void incrementalWriteDoesNotMarkComplete() throws Exception {
        // No-leak invariant: mid-wizard writes must never set complete=true, so a
        // crash before the final commit leaves a resumable (not falsely finished)
        // config. complete=true is set only at persistCommit.
        File binFolder = temp.newFolder("incomplete-flag");

        invokePersistIncremental(new CreateBinFileAnalysis(), binFolder, twoChannelConfig(), null,
                1, "Channel Identity", -1, null);

        ChannelConfig cfg = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertNull(cfg.complete);
        assertFalse(ChannelConfigIO.isComplete(cfg));
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
    public void finalCommitPreservesUnknownExtrasAndCustomSettings() throws Exception {
        File binFolder = temp.newFolder("commit-extras");
        ChannelConfig seeded = ChannelConfigIO.fromBinUserConfig(twoChannelConfig());
        seeded.extras.put("futureRootKey", "keep");
        seeded.channels.get(0).extras.put("futureChannelKey", "keep-channel");
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder), seeded);

        invokePersistCommit(new CreateBinFileAnalysis(), binFolder, twoChannelConfig(),
                new boolean[][]{{true, false}, {false, true}});

        ChannelConfig cfg = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals("keep", cfg.extras.get("futureRootKey"));
        assertEquals("keep-channel", cfg.channels.get(0).extras.get("futureChannelKey"));
        assertEquals(6, ((Number) cfg.extras.get("lastStepIndex")).intValue());
        assertTrue(cfg.extras.containsKey("customSettings"));
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
    public void saveAndExitExtractsVisibleDraftWithMarkerQcAndPageState() throws Exception {
        File binFolder = temp.newFolder("cancel-visible-draft");
        final CreateBinFileAnalysis.BinUserConfig visible = twoChannelConfig();
        visible.names.set(1, "IBA1 edited");
        visible.markerIds.set(0, "nuclei_dapi");
        visible.markerShapes.set(0, "round");
        visible.markerCrowdingSensitive.set(0, Boolean.TRUE);
        visible.objectThresholds.set(0, "321.5");
        visible.intensityThresholds.set(0, "321.5");
        visible.sizes.set(0, "25-900");
        visible.segmentationMethods.set(0,
                "stardist:0.61:0.27:linking=4.5:frameGap=2");
        visible.segmentationMethods.set(1,
                "cellpose:18.5:cyto3:0.42:-1.25:gpu=false:chan2=0");
        visible.qcSelectedSeriesIds.addAll(Arrays.asList("series-b", "series-a"));
        visible.qcSelectionMode = "Manually select images";
        visible.qcSelectionAlgorithm = "exact-series-ids-v1";
        visible.qcRandomCount = 7;
        visible.qcRecomputeMinMax = true;
        final boolean[][] pageSettings = new boolean[7][2];
        pageSettings[0][1] = true;
        pageSettings[6][0] = true;
        CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.SAVE_AND_EXIT);

        assertTrue(analysis.handleCancelRequest(null, binFolder, twoChannelConfig(), null,
                5, "Quality Check", new CreateBinFileAnalysis.WizardDraftExtractor() {
                    @Override public CreateBinFileAnalysis.WizardDraft extract() {
                        return new CreateBinFileAnalysis.WizardDraft(visible, pageSettings);
                    }
                }));

        ChannelConfig saved = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertNotNull(saved);
        assertFalse(ChannelConfigIO.isComplete(saved));
        assertEquals("IBA1 edited", saved.channels.get(1).name);
        assertEquals("nuclei_dapi", saved.channels.get(0).markerId);
        assertEquals("round", saved.channels.get(0).markerShape);
        assertTrue(saved.channels.get(0).markerCrowdingSensitive);
        assertEquals("321.5", saved.channels.get(0).threshold);
        assertEquals("321.5", saved.channels.get(0).intensityThreshold);
        assertEquals("25-900", saved.channels.get(0).size);
        assertEquals("stardist:0.61:0.27:linking=4.5:frameGap=2",
                saved.channels.get(0).segmentationMethod);
        assertEquals("cellpose:18.5:cyto3:0.42:-1.25:gpu=false:chan2=0",
                saved.channels.get(1).segmentationMethod);
        assertEquals(5, ((Number) saved.extras.get("lastStepIndex")).intValue());
        assertEquals("Quality Check", saved.extras.get("lastStepLabel"));
        assertEquals(Arrays.asList("series-b", "series-a"),
                saved.extras.get("qcSelectedSeriesIds"));
        assertEquals(7, ((Number) saved.extras.get("qcRandomCount")).intValue());
        assertEquals(Boolean.TRUE, saved.extras.get("qcRecomputeMinMax"));

        CreateBinFileAnalysis.WizardResumeState resumed =
                new CreateBinFileAnalysis().readWizardResumeState(binFolder);
        assertNotNull(resumed);
        assertEquals("IBA1 edited", resumed.cfg.names.get(1));
        assertEquals("nuclei_dapi", resumed.cfg.markerIds.get(0));
        assertEquals(Arrays.asList("series-b", "series-a"), resumed.cfg.qcSelectedSeriesIds);
        assertEquals(7, resumed.cfg.qcRandomCount);
        assertTrue(resumed.cfg.qcRecomputeMinMax);
        assertArrayEquals(pageSettings[6], resumed.customSettings[6]);
    }

    @Test
    public void saveAndExitFromThresholdSliderCapturesTheLiveMinimumForBothConsumers() throws Exception {
        File binFolder = temp.newFolder("cancel-threshold-slider");
        final CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        ByteProcessor processor = new ByteProcessor(2, 2);
        processor.setThreshold(37.0, 244.0, ImageProcessor.NO_LUT_UPDATE);
        ImagePlus preview = new ImagePlus("live-threshold", processor);
        final CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.SAVE_AND_EXIT);
        final CreateBinFileAnalysis.WizardDraft liveDraft =
                analysis.thresholdSliderDraft(cfg, 0, preview, cfg.objectThresholds.get(0));

        assertTrue(analysis.handleCancelRequest(null, binFolder, cfg, null,
                5, "Channel Threshold QC", new CreateBinFileAnalysis.WizardDraftExtractor() {
                    @Override public CreateBinFileAnalysis.WizardDraft extract() {
                        return liveDraft;
                    }
                }));

        ChannelConfig saved = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals("37", saved.channels.get(0).threshold);
        assertEquals("37", saved.channels.get(0).intensityThreshold);
    }

    @Test
    public void thresholdSliderDraftFallsBackToPersistedValueOnlyWhenSliderIsUnreadable() {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        ImagePlus previewWithoutThreshold = new ImagePlus("no-threshold", new ByteProcessor(2, 2));

        CreateBinFileAnalysis.WizardDraft draft = analysis.thresholdSliderDraft(
                cfg, 1, previewWithoutThreshold, "123.5");

        assertEquals("123.5", draft.cfg.objectThresholds.get(1));
        assertEquals("123.5", draft.cfg.intensityThresholds.get(1));
    }

    @Test
    public void saveAndExitFromBrightnessContrastCapturesLiveDisplayRange() throws Exception {
        File binFolder = temp.newFolder("cancel-display-range-slider");
        final CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        ImagePlus preview = new ImagePlus("live-display-range", new ByteProcessor(2, 2));
        preview.setDisplayRange(19.0, 201.0);
        final CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.SAVE_AND_EXIT);
        final CreateBinFileAnalysis.WizardDraft liveDraft =
                analysis.displayRangeSliderDraft(cfg, 0, preview);

        assertTrue(analysis.handleCancelRequest(null, binFolder, cfg, null,
                5, "Custom Min-Max Display Ranges",
                new CreateBinFileAnalysis.WizardDraftExtractor() {
                    @Override public CreateBinFileAnalysis.WizardDraft extract() {
                        return liveDraft;
                    }
                }));

        ChannelConfig saved = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals("19-201", saved.channels.get(0).minmax);
    }

    @Test
    public void saveAndExitFromCustomPresetNameCapturesTypedName() throws Exception {
        File binFolder = temp.newFolder("cancel-custom-preset-name");
        final CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        cfg.filterPresets.set(0, "Custom");
        final String macro = "run(\"Median...\", \"radius=2\");\n";
        final CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.SAVE_AND_EXIT);
        final CreateBinFileAnalysis.WizardDraft liveDraft =
                analysis.customFilterPresetDraft(
                        binFolder, cfg, 0, "  DAPI Cleanup  ", macro);

        assertTrue(analysis.handleCancelRequest(null, binFolder, cfg, null,
                5, "Save Custom Filter Preset",
                new CreateBinFileAnalysis.WizardDraftExtractor() {
                    @Override public CreateBinFileAnalysis.WizardDraft extract() {
                        return liveDraft;
                    }
                }));

        ChannelConfig saved = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals("DAPI Cleanup", saved.channels.get(0).filterPreset);
        File namedPreset = new File(
                FlashProjectLayout.forDirectory(binFolder.getAbsolutePath())
                        .customFilterPresetWriteDir(),
                "DAPI Cleanup.ijm");
        assertTrue(namedPreset.isFile());
        assertEquals(macro, new String(java.nio.file.Files.readAllBytes(namedPreset.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void failedCustomPresetPublicationDoesNotReplaceLastGoodJson() throws Exception {
        File binFolder = temp.newFolder("cancel-custom-preset-publication-failure");
        final CreateBinFileAnalysis.BinUserConfig previous = twoChannelConfig();
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder),
                ChannelConfigIO.fromBinUserConfig(previous));
        final CreateBinFileAnalysis.BinUserConfig visible = twoChannelConfig();
        visible.filterPresets.set(0, "Custom");
        final FailingPresetPublicationAnalysis analysis =
                new FailingPresetPublicationAnalysis();

        assertFalse(analysis.handleCancelRequest(null, binFolder, visible, null,
                5, "Save Custom Filter Preset",
                new CreateBinFileAnalysis.WizardDraftExtractor() {
                    @Override public CreateBinFileAnalysis.WizardDraft extract() {
                        return analysis.customFilterPresetDraft(
                                binFolder, visible, 0, "DAPI Cleanup", "macro bytes");
                    }
                }));

        ChannelConfig stillGood = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals("Default", stillGood.channels.get(0).filterPreset);
        assertTrue(ChannelConfigIO.isComplete(stillGood));
        assertTrue(analysis.rejection.contains("could not be published"));
    }

    @Test
    public void canceledNestedPresetNameStopsEnclosingFilterFlow() throws Exception {
        File binFolder = temp.newFolder("cancel-custom-preset-flow");
        CreateBinFileAnalysis.BinUserConfig cfg = twoChannelConfig();
        cfg.filterPresets.set(0, "Custom");
        CanceledPresetNameAnalysis analysis = new CanceledPresetNameAnalysis();

        boolean applied = invokeApplyCustomFilterEntryResult(
                analysis, binFolder, cfg, 0,
                CustomFilterEntryDialog.Result.imported("run(\"Median...\", \"radius=2\");", null),
                false);

        assertFalse(applied);
        assertEquals("Custom", cfg.filterPresets.get(0));
        assertTrue(new File(binFolder, "C1_Filters.ijm").isFile());
    }

    @Test
    public void saveAndExitRejectsInvalidVisibleDraftWithoutReplacingLastGoodJson() throws Exception {
        File binFolder = temp.newFolder("cancel-invalid-visible");
        final CreateBinFileAnalysis.BinUserConfig original = twoChannelConfig();
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder),
                ChannelConfigIO.fromBinUserConfig(original));
        final CreateBinFileAnalysis.BinUserConfig invalid = twoChannelConfig();
        invalid.names.set(1, "dapi");
        CancelChoiceAnalysis analysis = new CancelChoiceAnalysis(
                CancelConfirmationDialog.Choice.SAVE_AND_EXIT);

        assertFalse(analysis.handleCancelRequest(null, binFolder, original, null,
                1, "Channel Identity", new CreateBinFileAnalysis.WizardDraftExtractor() {
                    @Override public CreateBinFileAnalysis.WizardDraft extract() {
                        return new CreateBinFileAnalysis.WizardDraft(invalid, null);
                    }
                }));

        ChannelConfig stillGood = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals("DAPI", stillGood.channels.get(0).name);
        assertEquals("IBA1", stillGood.channels.get(1).name);
        assertTrue(ChannelConfigIO.isComplete(stillGood));
    }

    @Test
    public void saveAndExitKeepsPageOpenWhenAtomicCheckpointCannotBeVerified() throws Exception {
        File binFolder = temp.newFolder("cancel-checkpoint-failure");
        FailingCheckpointAnalysis analysis = new FailingCheckpointAnalysis();

        assertFalse(analysis.handleCancelRequest(null, binFolder, twoChannelConfig(), null,
                3, "Settings Mode", new CreateBinFileAnalysis.WizardDraftExtractor() {
                    @Override public CreateBinFileAnalysis.WizardDraft extract() {
                        return new CreateBinFileAnalysis.WizardDraft(twoChannelConfig(),
                                new boolean[7][2]);
                    }
                }));
        assertFalse(ChannelConfigIO.exists(FlashProjectLayout.settingsDir(binFolder)));
        assertTrue(analysis.rejection.contains("could not verify"));
    }

    @Test
    public void everyWizardCancelHookDeclaresAnExplicitDraftExtractor() throws Exception {
        File sourceFile = new File(
                "src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java");
        assertTrue(sourceFile.isFile());
        String source = new String(java.nio.file.Files.readAllBytes(sourceFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(source.matches("(?s).*installWizardCancelHook\\s*\\([^,;()]+\\s*\\);.*"));
        assertTrue(source.contains("captureMetadataReviewDraft(visible, rows, model, table)"));
        assertTrue(source.contains("buildStarDistMethod(visibleParameters)"));
        assertTrue(source.contains("buildCellposeMethod("));
        assertTrue(source.contains("ParticleSizeStage.isValidSizeFields("));
        assertTrue(source.contains("normalizeThresholdToken(thresholdField.getText())"));
        assertTrue(source.contains("thresholdSliderDraft(cfg, channelIndex, anchorImage"));
        assertTrue(source.contains("displayRangeSliderDraft(cfg, channelIndex, dup)"));
        assertTrue(source.contains("customFilterPresetDraft(binFolder, cfg, channelIndex"));
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
    public void handleFullCreationCancelSavePreservesPartialJsonAndResumeState() throws Exception {
        File projectRoot = temp.newFolder("handle-full-cancel");
        File binFolder = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath()).configurationWriteDir();
        assertTrue(binFolder.mkdirs());
        CancelDuringQcAnalysis analysis = new CancelDuringQcAnalysis();

        analysis.handleFullCreation(projectRoot.getAbsolutePath(), binFolder, null);

        ChannelConfig cfg = ChannelConfigIO.read(binFolder);
        assertNotNull(cfg);
        assertEquals(2, cfg.channels.size());
        assertEquals("DAPI", cfg.channels.get(0).name);
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                cfg.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                cfg.channels.get(0).statusOf(ChannelConfig.P_SIZE));
        assertEquals("IBA1", cfg.channels.get(1).name);
        assertEquals("Green", cfg.channels.get(1).color);
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                cfg.channels.get(1).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(5, ((Number) cfg.extras.get("lastStepIndex")).intValue());

        CreateBinFileAnalysis.WizardResumeState resume =
                new CreateBinFileAnalysis().readWizardResumeState(projectRoot.getAbsolutePath(), binFolder);
        assertNotNull(resume);
        assertEquals(5, resume.stepIndex);
        assertEquals(cfg.channels.get(0).name, resume.cfg.names.get(0));
        assertEquals(cfg.channels.get(1).color, resume.cfg.colors.get(1));
        assertEquals(cfg.channels.get(1).threshold, resume.cfg.objectThresholds.get(1));
    }

    @Test
    public void resumeStateWarnsWhenCurrentChannelCountDiffers() throws Exception {
        File binFolder = temp.newFolder("resume-count-mismatch");
        ChannelConfig cfg = ChannelConfigIO.fromBinUserConfig(twoChannelConfig());
        cfg.channels.get(0).status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        cfg.extras.put("lastStepIndex", Integer.valueOf(2));
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder), cfg);

        CreateBinFileAnalysis.WizardResumeState state =
                new ChannelCountAnalysis(3).readWizardResumeState("current-project", binFolder);

        assertNotNull(state);
        assertTrue(state.progressLines.get(0).contains("Current image metadata reports 3 channels"));
        assertTrue(state.progressLines.get(0).contains("Start Over"));
    }

    @Test
    public void futureSchemaVersionDoesNotCrashResumePrelude() throws Exception {
        File binFolder = temp.newFolder("future-schema");
        File settingsDir = FlashProjectLayout.settingsDir(binFolder);
        assertTrue(settingsDir.mkdirs());
        java.nio.file.Files.write(new File(settingsDir, ChannelConfigIO.FILE_NAME).toPath(),
                Arrays.asList("{\"schemaVersion\":2,\"channels\":[]}"),
                java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(null, new CreateBinFileAnalysis().readWizardResumeState(binFolder));
    }

    @Test
    public void channelCountChangeDropsStalePerChannelStatuses() throws Exception {
        File binFolder = temp.newFolder("channel-count-change");
        ChannelConfig seeded = ChannelConfigIO.fromBinUserConfig(twoChannelConfig());
        seeded.channels.add(ChannelConfigIO.fromBinUserConfig(twoChannelConfig()).channels.get(0));
        for (ChannelConfig.Channel channel : seeded.channels) {
            channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.CONFIGURED);
        }
        ChannelConfigIO.write(FlashProjectLayout.settingsDir(binFolder), seeded);

        invokePersistIncremental(new CreateBinFileAnalysis(), binFolder, twoChannelConfig(), null,
                1, "Channel Identity", -1, null);

        ChannelConfig cfg = ChannelConfigIO.read(FlashProjectLayout.settingsDir(binFolder));
        assertEquals(2, cfg.channels.size());
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                cfg.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.CONFIGURED,
                cfg.channels.get(0).statusOf(ChannelConfig.P_NAME));
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

    private static boolean invokeApplyCustomFilterEntryResult(
            CreateBinFileAnalysis analysis,
            File binFolder,
            CreateBinFileAnalysis.BinUserConfig cfg,
            int channelIndex,
            CustomFilterEntryDialog.Result result,
            boolean writeConfigOnDemote) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "applyCustomFilterEntryResult",
                File.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                int.class,
                CustomFilterEntryDialog.Result.class,
                boolean.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(analysis, binFolder, cfg, Integer.valueOf(channelIndex),
                result, Boolean.valueOf(writeConfigOnDemote))).booleanValue();
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

    private static final class ChannelCountAnalysis extends CreateBinFileAnalysis {
        private final int count;

        ChannelCountAnalysis(int count) {
            this.count = count;
        }

        @Override
        protected int detectCurrentChannelCount(String directory) {
            return count;
        }
    }

    private static final class FailingCheckpointAnalysis extends CreateBinFileAnalysis {
        String rejection = "";

        @Override
        protected CancelConfirmationDialog.Choice showCancelConfirmation(
                java.awt.Window owner, String stepLabel, List<String> progressLines,
                String draftPath) {
            return CancelConfirmationDialog.Choice.SAVE_AND_EXIT;
        }

        @Override
        protected long writeDraftCheckpoint(File binFolder, BinUserConfig cfg,
                                            boolean[][] customSettings, int step,
                                            String label) {
            return 0L;
        }

        @Override
        protected void rejectDraftSave(String message) {
            rejection = message == null ? "" : message;
        }
    }

    private static final class CanceledPresetNameAnalysis extends CreateBinFileAnalysis {
        @Override
        protected String promptForCustomFilterPresetName(
                java.awt.Window owner, File binFolder, BinUserConfig cfg, int channelIndex,
                String channelLabel, String defaultName, String macroContent) {
            return null;
        }
    }

    private static final class FailingPresetPublicationAnalysis extends CreateBinFileAnalysis {
        String rejection = "";

        @Override
        protected CancelConfirmationDialog.Choice showCancelConfirmation(
                java.awt.Window owner, String stepLabel, List<String> progressLines,
                String draftPath) {
            return CancelConfirmationDialog.Choice.SAVE_AND_EXIT;
        }

        @Override
        protected void publishCustomFilterPresetDraft(
                File binFolder, String presetName, String macroContent) throws java.io.IOException {
            throw new java.io.IOException("synthetic preset write failure");
        }

        @Override
        protected void rejectDraftSave(String message) {
            rejection = message == null ? "" : message;
        }
    }

    private static final class CancelDuringQcAnalysis extends CreateBinFileAnalysis {
        @Override
        protected BinUserConfig collectBinConfigFromUser(String directory, File binFolder,
                                                        flash.pipeline.bin.BinConfig existing,
                                                        BinUserConfig draft) {
            return twoChannelConfig();
        }

        @Override
        protected Boolean showAnalysisScopeDialog(BinUserConfig cfg, boolean allowBack) {
            return Boolean.FALSE;
        }

        @Override
        protected boolean[][] showGranularCustomFork(BinUserConfig cfg,
                                                     boolean showFilterParameters,
                                                     boolean showMinMax,
                                                     boolean showThreshold,
                                                     boolean showParticleSize,
                                                     boolean showSegmentationMethod,
                                                     boolean[][] initialSettings) {
            boolean[][] settings = new boolean[6][cfg.names.size()];
            settings[2][0] = true;
            settings[2][1] = true;
            return settings;
        }

        @Override
        protected QcImageOpenResult openImagesForQC(String directory, File binFolder,
                                                    BinUserConfig cfg, boolean[][] customSettings) {
            ChannelConfig partial = ChannelConfigIO.fromBinUserConfig(cfg);
            partial.extras.put("lastStepIndex", Integer.valueOf(5));
            partial.extras.put("lastStepLabel", "Quality Check");
            for (ChannelConfig.Channel channel : partial.channels) {
                for (String key : propertyKeys()) {
                    channel.status.put(key, ChannelConfig.PropertyStatus.PENDING);
                }
                channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
                channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
                channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.CONFIGURED);
            }
            ChannelConfig.Channel first = partial.channels.get(0);
            first.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.CONFIGURED);
            first.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.CONFIGURED);
            first.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.CONFIGURED);
            first.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.CONFIGURED);
            first.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.CONFIGURED);
            first.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.CONFIGURED);
            try {
                ChannelConfigIO.write(binFolder, partial);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            return QcImageOpenResult.cancel("");
        }

        @Override
        protected CancelConfirmationDialog.Choice showCancelConfirmation(
                java.awt.Window owner,
                String stepLabel,
                List<String> progressLines,
                String draftPath) {
            return CancelConfirmationDialog.Choice.SAVE_AND_EXIT;
        }
    }
}
