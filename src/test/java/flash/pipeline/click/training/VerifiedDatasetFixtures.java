package flash.pipeline.click.training;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.SegmentationFingerprint;
import flash.pipeline.click.training.cellpose.CellposeDatasetPackager;
import flash.pipeline.click.training.stardist.StarDistDatasetPackager;
import java.io.IOException;
import java.nio.file.Path;

/** Existing synthetic fixtures now explicitly capture the identity of their label provider. */
public final class VerifiedDatasetFixtures {
    private VerifiedDatasetFixtures() { }

    private static ClickStore capture(ClickStore store, ImagePlusProvider labels) {
        ClickStore verified = new ClickStore();
        if (store != null && labels != null) {
            for (ClickStore.Click c : store.all()) {
                verified.add(new ClickStore.Click(c.imageName, c.channelOneBased,
                        c.label, c.z, c.x, c.y, c.verdict, c.timestampMs,
                        SegmentationFingerprint.of(labels.get(c.imageName))));
            }
        }
        return verified;
    }

    public static CellposeDatasetPackager.PackagingResult cellpose(Path root, String name,
            int channel, ClickStore clicks, ImagePlusProvider raw,
            ImagePlusProvider labels, String model) throws IOException {
        return new CellposeDatasetPackager().packageDataset(root, name, channel,
                capture(clicks, labels), raw, labels, model);
    }

    public static StarDistDatasetPackager.PackagingResult stardist(Path root, String name,
            int channel, ClickStore clicks, ImagePlusProvider raw,
            ImagePlusProvider labels) throws IOException {
        return stardist(root, name, channel, clicks, raw, labels, 0);
    }

    public static StarDistDatasetPackager.PackagingResult stardist(Path root, String name,
            int channel, ClickStore clicks, ImagePlusProvider raw,
            ImagePlusProvider labels, int tileSize) throws IOException {
        return new StarDistDatasetPackager().packageDataset(root, name, channel,
                capture(clicks, labels), raw, labels, tileSize);
    }
}
