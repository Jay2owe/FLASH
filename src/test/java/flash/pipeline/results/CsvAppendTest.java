package flash.pipeline.results;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CsvAppendTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void append_rejectsMismatchedHeadersWithoutChangingDestination() throws Exception {
        File dest = temp.newFile("dest.csv");
        File src = temp.newFile("src.csv");
        Files.write(dest.toPath(), Arrays.asList("Animal,ROI,Area", "A,1,10"),
                StandardCharsets.UTF_8);
        Files.write(src.toPath(), Arrays.asList("Animal,Area", "B,20"),
                StandardCharsets.UTF_8);

        try {
            CsvAppend.append(dest, src);
            fail("Expected header mismatch to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("CSV headers do not match"));
        }

        assertEquals(Arrays.asList("Animal,ROI,Area", "A,1,10"),
                Files.readAllLines(dest.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void append_copiesSourceWhenDestinationIsEmpty() throws Exception {
        File dest = temp.newFile("dest-empty.csv");
        File src = temp.newFile("src.csv");
        Files.write(src.toPath(), Arrays.asList("Animal,ROI", "A,1"),
                StandardCharsets.UTF_8);

        CsvAppend.append(dest, src);

        assertEquals(Arrays.asList("Animal,ROI", "A,1"),
                Files.readAllLines(dest.toPath(), StandardCharsets.UTF_8));
    }
}
