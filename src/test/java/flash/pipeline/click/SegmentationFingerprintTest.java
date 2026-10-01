package flash.pipeline.click;

import flash.pipeline.click.training.cellpose.CellposeDatasetPackager;
import flash.pipeline.click.training.stardist.StarDistDatasetPackager;
import ij.ImagePlus;
import ij.process.ShortProcessor;
import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SegmentationFingerprintTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void identicalLabelNumberCannotReferToAReplacementObject() throws Exception {
        ImagePlus labels = labels();
        ClickStore store = clicks(SegmentationFingerprint.of(labels));
        labels.getProcessor().set(0, 0);
        labels.getProcessor().set(1, 1);
        assertExportFails(store, labels, "Segmentation has changed");
    }

    @Test public void legacyClicksRemainReadableButCannotTrainWithoutVerification() throws Exception {
        File folder = temp.newFolder();
        ClicksConfigIO.write(folder, clicks(""));
        ClickStore read = ClicksConfigIO.read(folder);
        assertEquals(1, read.all().size());
        assertExportFails(read, labels(), "no segmentation fingerprint");
    }

    @Test public void fingerprintRoundTripSurvivesTitleAndPixelStorageTypeChanges() throws Exception {
        ImagePlus labels = labels();
        String fingerprint = SegmentationFingerprint.of(labels);
        File folder = temp.newFolder();
        ClicksConfigIO.write(folder, clicks(fingerprint));
        ClickStore read = ClicksConfigIO.read(folder);
        assertEquals(fingerprint, read.all().get(0).segmentationFingerprint);
        labels.setTitle("renamed");
        labels.setProcessor(labels.getProcessor().convertToFloatProcessor());
        SegmentationFingerprint.requireMatching(read.all(), labels);
    }

    private void assertExportFails(ClickStore clicks, ImagePlus labels, String message)
            throws Exception {
        ImagePlus raw = new ImagePlus("raw", new ShortProcessor(2, 2));
        for (boolean starDist : new boolean[]{false, true}) {
            File root = temp.newFolder();
            try {
                if (starDist) {
                    new StarDistDatasetPackager().packageDataset(root.toPath(), "blocked",
                            1, clicks, name -> raw, name -> labels);
                } else {
                    new CellposeDatasetPackager().packageDataset(root.toPath(), "blocked",
                            1, clicks, name -> raw, name -> labels, "cyto3");
                }
                fail("Unverifiable object selections were exported");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(message));
                assertEquals(0, root.list().length);
            }
        }
    }

    private static ImagePlus labels() {
        ShortProcessor ip = new ShortProcessor(2, 2);
        ip.set(0, 1);
        return new ImagePlus("labels", ip);
    }

    private static ClickStore clicks(String fingerprint) {
        ClickStore store = new ClickStore();
        store.add(new ClickStore.Click("image", 1, 1, 1, 0, 0,
                ClickStore.Verdict.NEGATIVE, 1L, fingerprint));
        return store;
    }
}
