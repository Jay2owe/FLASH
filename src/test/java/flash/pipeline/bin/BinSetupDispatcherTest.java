package flash.pipeline.bin;

import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.image.NamedFilterLoader;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class BinSetupDispatcherTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetHooks() {
        BinSetupDispatcher.resetForTest();
    }

    @Test
    public void missingFields_reportsOnlyRequiredMissingFields() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("GFP");
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Green");

        EnumSet<BinField> missing = BinSetupDispatcher.missingFields(cfg,
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.CHANNEL_COLORS,
                        BinField.OBJECT_THRESHOLDS, BinField.Z_SLICE));

        assertEquals(EnumSet.of(BinField.OBJECT_THRESHOLDS, BinField.Z_SLICE), missing);
    }

    @Test
    public void completeBinReturnsCompletedWithoutShowingChooser() throws Exception {
        File dir = temp.newFolder("complete");
        writeCompleteConfig(dir);

        final AtomicInteger chooserCalls = new AtomicInteger(0);
        BinSetupDispatcher.setChooserForTest(new BinSetupDispatcher.Chooser() {
            @Override public BinSetupChooser.Choice show(String analysisDisplayName,
                                                         Set<BinField> missing,
                                                         boolean showRoiTip) {
                chooserCalls.incrementAndGet();
                return BinSetupChooser.Choice.CANCELLED;
            }
        });

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Test",
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.CHANNEL_COLORS,
                        BinField.OBJECT_THRESHOLDS, BinField.PARTICLE_SIZES,
                        BinField.DISPLAY_MIN_MAX, BinField.INTENSITY_THRESHOLDS,
                        BinField.SEGMENTATION_METHODS, BinField.FILTER_PRESETS,
                        BinField.Z_SLICE),
                false);

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, outcome);
        assertEquals(0, chooserCalls.get());
        assertEquals(BinSetupDispatcher.SOURCE_LOADED,
                BinSetupDispatcher.getLastFieldSources().get(BinField.CHANNEL_NAMES));
        assertEquals(BinSetupDispatcher.SOURCE_LOADED,
                BinSetupDispatcher.getLastFieldSources().get(BinField.OBJECT_THRESHOLDS));
    }

    @Test
    public void presentationPartialConfigDoesNotSatisfyThreeDObjectRequirements() throws Exception {
        File dir = temp.newFolder("presentationPartial");
        writePresentationPartialConfig(dir);
        BinSetupDispatcher.setHeadlessProbeForTest(new BinSetupDispatcher.HeadlessProbe() {
            @Override public boolean isHeadlessOrMacro() {
                return false;
            }
        });

        final AtomicInteger chooserCalls = new AtomicInteger(0);
        BinSetupDispatcher.setChooserForTest(new BinSetupDispatcher.Chooser() {
            @Override public BinSetupChooser.Choice show(String analysisDisplayName,
                                                         Set<BinField> missing,
                                                         boolean showRoiTip) {
                chooserCalls.incrementAndGet();
                return BinSetupChooser.Choice.CANCELLED;
            }
        });

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Split & Merge Image Channels",
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.CHANNEL_COLORS,
                        BinField.DISPLAY_MIN_MAX, BinField.Z_SLICE),
                false));
        assertEquals(0, chooserCalls.get());

        BinSetupDispatcher.setChooserForTest(new BinSetupDispatcher.Chooser() {
            @Override public BinSetupChooser.Choice show(String analysisDisplayName,
                                                         Set<BinField> missing,
                                                         boolean showRoiTip) {
                chooserCalls.incrementAndGet();
                assertEquals("3D Object Analysis", analysisDisplayName);
                assertEquals(EnumSet.of(BinField.OBJECT_THRESHOLDS,
                                BinField.PARTICLE_SIZES,
                                BinField.SEGMENTATION_METHODS,
                                BinField.FILTER_PRESETS),
                        missing);
                return BinSetupChooser.Choice.CANCELLED;
            }
        });

        assertEquals(BinSetupDispatcher.Outcome.CANCELLED, BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "3D Object Analysis",
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.CHANNEL_COLORS,
                        BinField.OBJECT_THRESHOLDS, BinField.PARTICLE_SIZES,
                        BinField.SEGMENTATION_METHODS, BinField.FILTER_PRESETS,
                        BinField.Z_SLICE),
                true));
        assertEquals(1, chooserCalls.get());
    }

    @Test
    public void headlessModeWritesMissingFieldsFromCliWithoutChooser() throws Exception {
        File dir = temp.newFolder("macro");
        final AtomicInteger chooserCalls = new AtomicInteger(0);
        BinSetupDispatcher.setHeadlessProbeForTest(new BinSetupDispatcher.HeadlessProbe() {
            @Override public boolean isHeadlessOrMacro() {
                return true;
            }
        });
        BinSetupDispatcher.setChooserForTest(new BinSetupDispatcher.Chooser() {
            @Override public BinSetupChooser.Choice show(String analysisDisplayName,
                                                         Set<BinField> missing,
                                                         boolean showRoiTip) {
                chooserCalls.incrementAndGet();
                return BinSetupChooser.Choice.FULL;
            }
        });

        CLIConfig cli = CLIArgumentParser.parse("dir=[" + dir.getAbsolutePath() + "] "
                + "channel_names=DAPI,GFP intensity_thresholds=default,500 z_slice_mode=full");
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Intensity Analysis",
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.INTENSITY_THRESHOLDS, BinField.Z_SLICE),
                false, false, cli);

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, outcome);
        assertEquals(0, chooserCalls.get());
        assertEquals(BinSetupDispatcher.SOURCE_CLI_ARGUMENT,
                BinSetupDispatcher.getLastFieldSources().get(BinField.CHANNEL_NAMES));
        assertEquals(BinSetupDispatcher.SOURCE_CLI_ARGUMENT,
                BinSetupDispatcher.getLastFieldSources().get(BinField.INTENSITY_THRESHOLDS));

        BinConfig written = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());
        assertEquals(java.util.Arrays.asList("DAPI", "GFP"), written.channelNames);
        assertEquals(java.util.Arrays.asList("default", "500"), written.channelIntensityThresholds);
        assertEquals(flash.pipeline.zslice.ZSliceMode.FULL, written.zSliceMode);
    }

    @Test
    public void unattendedModeWithoutCliArgsFailsWithPreciseMessage() throws Exception {
        File dir = temp.newFolder("unattended");
        BinSetupDispatcher.setHeadlessProbeForTest(new BinSetupDispatcher.HeadlessProbe() {
            @Override public boolean isHeadlessOrMacro() {
                return true;
            }
        });

        try {
            BinSetupDispatcher.ensure(
                    dir.getAbsolutePath(), "Intensity Analysis",
                    EnumSet.of(BinField.CHANNEL_NAMES, BinField.INTENSITY_THRESHOLDS, BinField.Z_SLICE),
                    false, true);
            fail("Expected missing channel_names");
        } catch (IllegalArgumentException e) {
            assertEquals("Cannot run Intensity Analysis: missing parameter `channel_names`. "
                    + "Pass `channel_names=...` on the command line, or run interactively first.",
                    e.getMessage());
        }
    }

    @Test
    public void suppressDialogsInInteractiveModeStillShowsMissingBinChooser() throws Exception {
        File dir = temp.newFolder("suppressedInteractive");
        final AtomicInteger chooserCalls = new AtomicInteger(0);
        BinSetupDispatcher.setHeadlessProbeForTest(new BinSetupDispatcher.HeadlessProbe() {
            @Override public boolean isHeadlessOrMacro() {
                return false;
            }
        });
        BinSetupDispatcher.setChooserForTest(new BinSetupDispatcher.Chooser() {
            @Override public BinSetupChooser.Choice show(String analysisDisplayName,
                                                         Set<BinField> missing,
                                                         boolean showRoiTip) {
                chooserCalls.incrementAndGet();
                assertEquals(EnumSet.of(BinField.CHANNEL_NAMES,
                                BinField.INTENSITY_THRESHOLDS,
                                BinField.Z_SLICE),
                        missing);
                return BinSetupChooser.Choice.CANCELLED;
            }
        });

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Intensity Analysis",
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.INTENSITY_THRESHOLDS, BinField.Z_SLICE),
                false, true);

        assertEquals(BinSetupDispatcher.Outcome.CANCELLED, outcome);
        assertEquals(1, chooserCalls.get());
    }

    @Test
    public void headlessModeWritesFilterPresetMacrosFromCliValues() throws Exception {
        File dir = temp.newFolder("headlessFilters");
        BinSetupDispatcher.setHeadlessProbeForTest(new BinSetupDispatcher.HeadlessProbe() {
            @Override public boolean isHeadlessOrMacro() {
                return true;
            }
        });

        CLIConfig cli = CLIArgumentParser.parse("dir=[" + dir.getAbsolutePath() + "] "
                + "channel_names=DAPI,Iba1 channel_colors=Cyan,Green "
                + "object_thresholds=default,1500 particle_sizes=100-Infinity,200-1000 "
                + "segmentation_methods=classical,classical "
                + "filter_presets=default,ramified_cells_microglia_astrocytes z_slice_mode=full");
        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "3D Object Analysis",
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.CHANNEL_COLORS,
                        BinField.OBJECT_THRESHOLDS, BinField.PARTICLE_SIZES,
                        BinField.SEGMENTATION_METHODS, BinField.FILTER_PRESETS,
                        BinField.Z_SLICE),
                true, false, cli);

        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, outcome);
        File bin = configurationDir(dir);
        assertEquals(normalize(NamedFilterLoader.loadFilterContent("Ramified Cells (Microglia/Astrocytes)")),
                normalize(new String(Files.readAllBytes(new File(bin, "C2_Filters.ijm").toPath()),
                        StandardCharsets.UTF_8)));
    }

    @Test
    public void roiTipShownOnlyWhenAnalysisBenefitsAndNoRoisExist() throws Exception {
        File noRois = temp.newFolder("noRois");
        File withRois = temp.newFolder("withRois");
        File roiDir = new File(withRois, "FLASH/Results/Analysis Images/ROIs");
        assertTrue(roiDir.mkdirs());
        Files.write(new File(roiDir, "SCN ROIs.zip").toPath(), new byte[]{1, 2, 3});

        BinSetupDispatcher.setHeadlessProbeForTest(new BinSetupDispatcher.HeadlessProbe() {
            @Override public boolean isHeadlessOrMacro() {
                return false;
            }
        });

        final AtomicBoolean lastRoiTip = new AtomicBoolean(false);
        BinSetupDispatcher.setChooserForTest(new BinSetupDispatcher.Chooser() {
            @Override public BinSetupChooser.Choice show(String analysisDisplayName,
                                                         Set<BinField> missing,
                                                         boolean showRoiTip) {
                lastRoiTip.set(showRoiTip);
                return BinSetupChooser.Choice.CANCELLED;
            }
        });

        BinSetupDispatcher.ensure(noRois.getAbsolutePath(), "Intensity",
                EnumSet.of(BinField.CHANNEL_NAMES), true);
        assertTrue(lastRoiTip.get());

        BinSetupDispatcher.ensure(withRois.getAbsolutePath(), "Intensity",
                EnumSet.of(BinField.CHANNEL_NAMES), true);
        assertFalse(lastRoiTip.get());

        BinSetupDispatcher.ensure(noRois.getAbsolutePath(), "Intensity",
                EnumSet.of(BinField.CHANNEL_NAMES), false);
        assertFalse(lastRoiTip.get());
    }

    @Test
    public void choicesRouteToFullPartialBypassAndCancel() throws Exception {
        BinSetupDispatcher.setHeadlessProbeForTest(new BinSetupDispatcher.HeadlessProbe() {
            @Override public boolean isHeadlessOrMacro() {
                return false;
            }
        });

        final AtomicReference<Set<BinField>> wizardFields = new AtomicReference<Set<BinField>>();
        final AtomicReference<Set<BinField>> bypassFields = new AtomicReference<Set<BinField>>();
        BinSetupDispatcher.setWizardRunnerForTest(new BinSetupDispatcher.WizardRunner() {
            @Override public void run(String directory, Set<BinField> fields) {
                wizardFields.set(fields);
                try {
                    writeCompleteConfig(new File(directory));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        BinSetupDispatcher.setBypassRunnerForTest(new BinSetupDispatcher.BypassRunner() {
            @Override public boolean show(String directory, Set<BinField> fields) {
                bypassFields.set(fields);
                try {
                    writeNamesOnlyConfig(new File(directory));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return true;
            }
        });

        File fullDir = temp.newFolder("routesFull");
        setChoice(BinSetupChooser.Choice.FULL);
        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, BinSetupDispatcher.ensure(
                fullDir.getAbsolutePath(), "Test", EnumSet.of(BinField.CHANNEL_NAMES), false));
        assertEquals(BinField.all(), wizardFields.get());

        File partialDir = temp.newFolder("routesPartial");
        setChoice(BinSetupChooser.Choice.PARTIAL);
        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, BinSetupDispatcher.ensure(
                partialDir.getAbsolutePath(), "Test", EnumSet.of(BinField.CHANNEL_NAMES), false));
        assertEquals(EnumSet.of(BinField.CHANNEL_NAMES), wizardFields.get());
        assertEquals(BinSetupDispatcher.SOURCE_PROMPTED_PARTIAL,
                BinSetupDispatcher.getLastFieldSources().get(BinField.CHANNEL_NAMES));

        File bypassDir = temp.newFolder("routesBypass");
        setChoice(BinSetupChooser.Choice.BYPASS);
        assertEquals(BinSetupDispatcher.Outcome.COMPLETED, BinSetupDispatcher.ensure(
                bypassDir.getAbsolutePath(), "Test", EnumSet.of(BinField.CHANNEL_NAMES), false));
        assertEquals(EnumSet.of(BinField.CHANNEL_NAMES), bypassFields.get());
        assertEquals(BinSetupDispatcher.SOURCE_BYPASS_DIALOG,
                BinSetupDispatcher.getLastFieldSources().get(BinField.CHANNEL_NAMES));

        File cancelDir = temp.newFolder("routesCancel");
        setChoice(BinSetupChooser.Choice.CANCELLED);
        assertEquals(BinSetupDispatcher.Outcome.CANCELLED, BinSetupDispatcher.ensure(
                cancelDir.getAbsolutePath(), "Test", EnumSet.of(BinField.CHANNEL_NAMES), false));
        assertEquals(BinSetupDispatcher.Outcome.CANCELLED, BinSetupDispatcher.getLastOutcome());
    }

    @Test
    public void wizardThatClosesWithoutWritingRequiredConfigReturnsCancelled() throws Exception {
        File dir = temp.newFolder("incompleteWizard");
        BinSetupDispatcher.setHeadlessProbeForTest(new BinSetupDispatcher.HeadlessProbe() {
            @Override public boolean isHeadlessOrMacro() {
                return false;
            }
        });
        setChoice(BinSetupChooser.Choice.PARTIAL);
        BinSetupDispatcher.setWizardRunnerForTest(new BinSetupDispatcher.WizardRunner() {
            @Override public void run(String directory, Set<BinField> fields) {
            }
        });

        BinSetupDispatcher.Outcome outcome = BinSetupDispatcher.ensure(
                dir.getAbsolutePath(), "Intensity",
                EnumSet.of(BinField.CHANNEL_NAMES), false);

        assertEquals(BinSetupDispatcher.Outcome.CANCELLED, outcome);
        assertEquals(BinSetupDispatcher.Outcome.CANCELLED, BinSetupDispatcher.getLastOutcome());
    }

    private static void setChoice(final BinSetupChooser.Choice choice) {
        BinSetupDispatcher.setChooserForTest(new BinSetupDispatcher.Chooser() {
            @Override public BinSetupChooser.Choice show(String analysisDisplayName,
                                                         Set<BinField> missing,
                                                         boolean showRoiTip) {
                return choice;
            }
        });
    }

    private static void writeCompleteConfig(File dir) throws IOException {
        ChannelConfigIO.write(configurationDir(dir), completeConfig("DAPI"));
    }

    private static void writeNamesOnlyConfig(File dir) throws IOException {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.COMMITTED);
        cfg.channels.add(channel);
        ChannelConfigIO.write(configurationDir(dir), cfg);
    }

    private static void writePresentationPartialConfig(File dir) throws IOException {
        ChannelConfig cfg = new ChannelConfig();
        cfg.complete = Boolean.FALSE;
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.color = "Blue";
        channel.minmax = "10-200";
        channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
        channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
        channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.CONFIGURED);
        channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.CONFIGURED);
        channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.PENDING);
        cfg.channels.add(channel);
        ChannelConfigIO.write(configurationDir(dir), cfg);
    }

    private static ChannelConfig completeConfig(String name) {
        BinConfig bin = new BinConfig();
        bin.channelNames.add(name);
        bin.channelColors.add("Blue");
        bin.channelThresholds.add("default");
        bin.channelSizes.add("100-Infinity");
        bin.channelMinMax.add("None");
        bin.channelIntensityThresholds.add("default");
        bin.segmentationMethods.add("classical");
        bin.channelFilterPresets.add("Default");
        bin.zSliceConfigPresent = true;
        return ChannelConfigIO.fromBinConfig(bin);
    }

    private static File configurationDir(File dir) {
        return new File(dir, "FLASH/Config/.settings");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
