package flash.pipeline.bin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BinConfigCellposeTokenRoundTripTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void legacyReadsAsCatalogModelKeyAndWritesCanonical() throws Exception {
        File dir = writeLegacyBin("cellpose:30:cyto3:0.4:0.0:gpu=true:chan2=0");

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertTrue(cfg.isCellpose(0));
        assertEquals("cellpose_cyto3", cfg.getCellposeModel(0));

        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);

        assertEquals("cellpose:30:0.4:0.0:gpu=true:chan2=0:model=cellpose_cyto3",
                writtenSegmentationLine(dir));
    }

    @Test
    public void newCanonicalTokenRoundTripsIdentically() throws Exception {
        String token = "cellpose:22.0:0.6:-0.1:gpu=false:chan2=0:model=user_xyz";
        File dir = writeLegacyBin(token);

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);

        assertEquals(token, writtenSegmentationLine(dir));
    }

    private File writeLegacyBin(String segmentationLine) throws Exception {
        File dir = temp.newFolder();
        File bin = new File(dir, ".bin");
        assertTrue(bin.mkdirs());
        String content = "IBA1\n"
                + "Green\n"
                + "default\n"
                + "100-Infinity\n"
                + "None\n"
                + "default\n"
                + segmentationLine + "\n";
        Files.write(new File(bin, "Channel_Data.txt").toPath(),
                content.getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static String writtenSegmentationLine(File dir) throws Exception {
        File written = new File(dir, "FLASH/Set Up Configuration/.settings/Channel_Data.txt");
        List<String> lines = Files.readAllLines(written.toPath(), StandardCharsets.UTF_8);
        return lines.get(6);
    }
}
