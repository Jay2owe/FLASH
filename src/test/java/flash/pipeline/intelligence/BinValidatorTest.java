package flash.pipeline.intelligence;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BinValidatorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File writeConfig(String... channels) throws Exception {
        File dir = temp.newFolder("bin-validator");
        BinConfig cfg = TestConfigFiles.basicBinConfig(channels);
        TestConfigFiles.writeChannelConfig(dir, cfg);
        return dir;
    }

    @Test
    public void check_rejectsMissingChannelConfig() throws Exception {
        File dir = temp.newFolder("bin-validator-missing");

        BinValidator.Result result = BinValidator.check(dir.getAbsolutePath(), 0);

        assertFalse(result.ok);
        assertTrue(result.message.contains("No channel_config.json"));
    }

    @Test
    public void check_rejectsChannelCountMismatch() throws Exception {
        File dir = writeConfig("DAPI", "GFP");

        BinValidator.Result result = BinValidator.check(dir.getAbsolutePath(), 3);

        assertFalse(result.ok);
        assertTrue(result.message.contains("these images have 3"));
    }
}
