package flash.pipeline.bin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BinConfigIODelegationTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void readPartialPrefersChannelConfigJsonWhenPresent() throws Exception {
        File dir = temp.newFolder("preferJson");
        writeLegacyChannelData(dir, "LEGACY", "Red");
        ChannelConfigIO.write(settingsDir(dir),
                ChannelConfigIORoundTripTest.committedConfig("JSON", "Blue"));

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals("JSON", cfg.channelNames.get(0));
        assertEquals("Blue", cfg.channelColors.get(0));
    }

    @Test
    public void readPartialFallsThroughOnCorruptJson() throws Exception {
        File dir = temp.newFolder("corruptJson");
        writeLegacyChannelData(dir, "LEGACY", "Red");
        Files.write(new File(settingsDir(dir), ChannelConfigIO.FILE_NAME).toPath(),
                "{not json".getBytes(StandardCharsets.UTF_8));

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals("LEGACY", cfg.channelNames.get(0));
        assertEquals("Red", cfg.channelColors.get(0));
    }

    @Test
    public void readPartialUsesLegacyWhenJsonAbsent() throws Exception {
        File dir = temp.newFolder("legacyOnly");
        writeLegacyChannelData(dir, "LEGACY", "Green");

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals("LEGACY", cfg.channelNames.get(0));
        assertEquals("Green", cfg.channelColors.get(0));
    }

    private static File settingsDir(File dir) {
        return new File(dir, "FLASH/Config/.settings");
    }

    private static void writeLegacyChannelData(File dir, String name, String color) throws Exception {
        File settingsDir = settingsDir(dir);
        assertTrue(settingsDir.mkdirs());
        Files.write(new File(settingsDir, "Channel_Data.txt").toPath(),
                (name + "\n"
                        + color + "\n"
                        + "default\n"
                        + "100-Infinity\n").getBytes(StandardCharsets.UTF_8));
    }
}
