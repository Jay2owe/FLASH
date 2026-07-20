package flash.pipeline.audit;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.wizard.JsonIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeconvAuditTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void runDeconvReplayRoundTripsCliDeconvParameters() {
        String opts0 = "dir=[C:/data/Exp One] run_deconv deconv.enabled=true deconv.engine=DL2 "
                + "deconv.algorithm=RL_TV deconv.psf=GibsonLanni deconv.iterations=25 "
                + "deconv.regularization=0.01 deconv.scopeModality=confocal deconv.pinholeAU=1.0 "
                + "deconv.sampleRI=1.33 deconv.channels=[0,2] deconv.strictNyquist=true "
                + "deconv.useCache=false deconv.ch2.engine=CLIJ2 deconv.ch2.iterations=40";
        CLIConfig parsed0 = CLIArgumentParser.parse(opts0);

        String command = ReplayCommandFormatter.format("C:/data/Exp One", 2, new BinConfig(), parsed0);
        String options = extractOptions(command);
        CLIConfig parsed1 = CLIArgumentParser.parse(options);

        assertTrue(options.contains("run_deconv"));
        CLIConfig.DeconvConfig d0 = parsed0.getDeconv();
        CLIConfig.DeconvConfig d1 = parsed1.getDeconv();
        assertTrue(d1.isEnabled());
        assertEquals(d0.getEngine(), d1.getEngine());
        assertEquals(d0.getAlgorithm(), d1.getAlgorithm());
        assertEquals(d0.getPsfModel(), d1.getPsfModel());
        assertEquals(d0.getIterations(), d1.getIterations());
        assertEquals(d0.getScopeModality(), d1.getScopeModality());
        assertEquals(d0.getPinholeAiryUnits(), d1.getPinholeAiryUnits());
        assertEquals(d0.isStrictNyquist(), d1.isStrictNyquist());
        assertEquals(d0.isUseCache(), d1.isUseCache());
        assertArrayEquals(d0.getChannels(), d1.getChannels());
        // per-channel override round-trips
        assertEquals("CLIJ2", d1.getPerChannel().get(Integer.valueOf(2)).getEngine());
        assertEquals(Integer.valueOf(40), d1.getPerChannel().get(Integer.valueOf(2)).getIterations());
    }

    @Test
    public void guiRunEmitsBareRunDeconvWithoutCliOptions() {
        String command = ReplayCommandFormatter.format("C:/data/Exp", 2, new BinConfig());
        String options = extractOptions(command);
        assertTrue(options.contains("run_deconv"));
        assertFalse(options.contains("deconv.engine"));
        assertFalse(options.contains("deconv.enabled"));
    }

    @Test
    public void snapshotIncludesDeconvSectionFromChannelConfig() throws Exception {
        File dir = temp.newFolder("snapshot-deconv");

        ChannelConfig cfg = new ChannelConfig();
        cfg.complete = Boolean.TRUE;
        cfg.deconvOptics = new ChannelConfig.DeconvOptics();
        cfg.deconvOptics.na = Double.valueOf(1.4);
        cfg.deconvOptics.immersionRi = Double.valueOf(1.515);
        cfg.deconvOptics.sampleRi = Double.valueOf(1.47);
        cfg.deconvOptics.scopeModality = "WIDEFIELD";
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.deconvEngineKey = "clij2fft";
        channel.deconvAlgorithm = "RL_TV";
        channel.deconvIterations = Integer.valueOf(30);
        channel.emissionWavelengthNm = Double.valueOf(461.0);
        channel.routeAnalysis = "deconv";
        channel.routeDisplay = "raw";
        cfg.channels.add(channel);

        File settingsDir = FlashProjectLayout.forDirectory(dir.getAbsolutePath()).configurationWriteDir();
        ChannelConfigIO.write(settingsDir, cfg);

        RunSettingsSnapshot snapshot = RunSettingsSnapshot.create(
                dir.getAbsolutePath(), "3D Deconvolution", 2,
                EnumSet.noneOf(BinField.class), null, null);

        Map<String, Object> deconv = JsonIO.asObject(JsonIO.parseObject(snapshot.toJson()).get("deconv"));
        assertTrue(JsonIO.booleanValue(deconv.get("configured"), false));

        Map<String, Object> optics = JsonIO.asObject(deconv.get("optics"));
        assertEquals(1.4, JsonIO.doubleValue(optics.get("na"), -1), 1e-9);
        assertEquals("WIDEFIELD", optics.get("scope_modality"));

        List<Object> channels = JsonIO.asList(deconv.get("channels"));
        assertEquals(1, channels.size());
        Map<String, Object> row = JsonIO.asObject(channels.get(0));
        assertEquals("clij2fft", row.get("engine"));
        assertEquals("RL_TV", row.get("algorithm"));
        assertEquals(30, JsonIO.intValue(row.get("iterations"), -1));
        assertEquals("deconv", row.get("route_analysis"));
        assertEquals("raw", row.get("route_display"));
    }

    private static String extractOptions(String command) {
        int start = command.indexOf("\", \"") + 4;
        int end = command.lastIndexOf("\");");
        String escaped = command.substring(start, end);
        return escaped.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
