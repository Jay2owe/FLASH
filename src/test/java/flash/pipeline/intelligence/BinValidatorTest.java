package flash.pipeline.intelligence;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BinValidatorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File writeBin(String... lines) throws Exception {
        File dir = temp.newFolder("bin-validator");
        File binDir = new File(dir, ".bin");
        assertTrue(binDir.mkdirs());
        File channelData = new File(binDir, "Channel_Data.txt");
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(line).append('\n');
        }
        Files.write(channelData.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    public void check_rejectsMismatchedPerChannelLines() throws Exception {
        File dir = writeBin(
                "DAPI GFP RFP",
                "Blue Green",
                "default default default",
                "100-Infinity 100-Infinity 100-Infinity");

        BinValidator.Result result = BinValidator.check(dir.getAbsolutePath(), 0);

        assertFalse(result.ok);
        assertTrue(result.message.contains("Line 2"));
    }

    @Test
    public void check_rejectsChannelCountMismatch() throws Exception {
        File dir = writeBin(
                "DAPI GFP",
                "Blue Green",
                "default default",
                "100-Infinity 100-Infinity");

        BinValidator.Result result = BinValidator.check(dir.getAbsolutePath(), 3);

        assertFalse(result.ok);
        assertTrue(result.message.contains("these images have 3"));
    }
}
