package flash.pipeline.click.training;

import flash.pipeline.click.ClickStore;
import flash.pipeline.ui.wizard.JsonIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable copy of the reviewed selections actually used for a training export. */
public final class TrainingSelectionSnapshot {
    public static final String FILE_NAME = "training_selections.json";
    private TrainingSelectionSnapshot() { }

    public static void write(Path output, List<ClickStore.Click> selections) throws IOException {
        Map<String, Object> root = JsonIO.object();
        root.put("version", Integer.valueOf(1));
        root.put("fingerprintAlgorithm", "sha256-label-geometry-floatbits-v1");
        List<Object> rows = new ArrayList<Object>();
        for (ClickStore.Click c : selections) {
            Map<String, Object> row = JsonIO.object();
            row.put("image", c.imageName);
            row.put("channel", Integer.valueOf(c.channelOneBased));
            row.put("label", Integer.valueOf(c.label));
            row.put("z", Integer.valueOf(c.z));
            row.put("x", Double.valueOf(c.x));
            row.put("y", Double.valueOf(c.y));
            row.put("verdict", c.verdict == ClickStore.Verdict.POSITIVE ? "positive" : "negative");
            row.put("timestamp", Long.valueOf(c.timestampMs));
            row.put("segmentationFingerprint", c.segmentationFingerprint);
            rows.add(row);
        }
        root.put("clicks", rows);
        Files.write(output.resolve(FILE_NAME), Collections.singletonList(JsonIO.write(root)),
                StandardCharsets.UTF_8);
    }
}
