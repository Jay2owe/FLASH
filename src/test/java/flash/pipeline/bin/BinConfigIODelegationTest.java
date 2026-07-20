package flash.pipeline.bin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class BinConfigIODelegationTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void readPartialUsesChannelConfigJsonWhenPresent() throws Exception {
        File dir = temp.newFolder("json");
        ChannelConfigIO.write(settingsDir(dir),
                ChannelConfigIORoundTripTest.committedConfig("JSON", "Blue"));

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals("JSON", cfg.channelNames.get(0));
        assertEquals("Blue", cfg.channelColors.get(0));
    }

    @Test
    public void readPartialReturnsEmptyOnCorruptJson() throws Exception {
        File dir = temp.newFolder("corruptJson");
        assertEquals(true, settingsDir(dir).mkdirs());
        Files.write(new File(settingsDir(dir), ChannelConfigIO.FILE_NAME).toPath(),
                "{not json".getBytes(StandardCharsets.UTF_8));

        BinConfig cfg = BinConfigIO.readPartialFromDirectory(dir.getAbsolutePath());

        assertEquals(0, cfg.numChannels());
    }

    private static File settingsDir(File dir) {
        return new File(dir, "FLASH/Config/.settings");
    }

}
