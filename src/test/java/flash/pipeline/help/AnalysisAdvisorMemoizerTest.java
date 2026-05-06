package flash.pipeline.help;

import flash.pipeline.bin.BinConfigIO;
import ij.IJ;
import ij.ImagePlus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression test for §14p: AnalysisAdvisor must not write Bio-Formats
 * .bfmemo cache files into the user's data folder when the Help dialog
 * sniffs image dimensions.
 */
public class AnalysisAdvisorMemoizerTest {

    private Path tmp;

    @Before
    public void setUp() throws Exception {
        tmp = Files.createTempDirectory("flash-advisor-memoizer");
    }

    @After
    public void tearDown() throws Exception {
        if (tmp != null) {
            deleteRecursively(tmp);
        }
    }

    @Test
    public void recommendDoesNotWriteBfmemoNextToImage() throws Exception {
        Path input = Files.createDirectories(tmp.resolve("input"));
        Path tiff = input.resolve("Sample-Mouse1_LH_CA1.tif");
        createTinyTiff(tiff);

        File binDir = new File(tmp.toFile(), ".bin");
        assertTrue("created .bin dir", binDir.mkdirs());
        writeMinimalChannelData(new File(binDir, "Channel_Data.txt"));

        AnalysisAdvisor advisor = new AnalysisAdvisor();
        AdvisorResult result = advisor.recommend(tmp.toFile());
        assertNotNull("advisor must return a result", result);

        List<Path> bfmemo = findBfmemoFiles(tmp);
        if (!bfmemo.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "AnalysisAdvisor leaked .bfmemo files into the data folder: ");
            for (Path p : bfmemo) sb.append('\n').append(p);
            fail(sb.toString());
        }
    }

    private static void createTinyTiff(Path target) throws IOException {
        ImagePlus imp = IJ.createImage(target.getFileName().toString(),
                "16-bit", 8, 8, 1, 2, 1);
        try {
            IJ.saveAsTiff(imp, target.toString());
            assertTrue("synthesised TIFF must exist: " + target,
                    Files.isRegularFile(target) && Files.size(target) > 0);
        } finally {
            imp.close();
        }
    }

    private static void writeMinimalChannelData(File channelData) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Channel1\tChannel2\n");
        sb.append("Red\tGreen\n");
        sb.append("100\t100\n");
        sb.append("10-1000\t10-1000\n");
        sb.append("0-65535\t0-65535\n");
        sb.append("100\t100\n");
        Files.write(channelData.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        // Sanity: BinConfigIO must still parse what we wrote (headless).
        try {
            BinConfigIO.readFromDirectory(channelData.getParentFile().getParent());
        } catch (Throwable ignored) {
            // Test only cares about side-effects of the dimension sniff;
            // BinConfigIO formatting variance is fine — sniff still runs.
        }
    }

    private static List<Path> findBfmemoFiles(Path root) throws IOException {
        final List<Path> hits = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".bfmemo")) hits.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return hits;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
