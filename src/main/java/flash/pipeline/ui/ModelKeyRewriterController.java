package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelKeyRewriter;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Testable confirmation boundary for the destructive model-key rename action.
 */
public final class ModelKeyRewriterController {
    public interface Confirmation {
        boolean confirm(String warningMessage);
    }

    public ModelKeyRewriter.RenameResult rename(String oldKey,
                                                String newKey,
                                                Path projectRoot,
                                                Confirmation confirmation)
            throws IOException {
        String warning = warningMessage();
        if (confirmation == null || !confirmation.confirm(warning)) {
            return new ModelKeyRewriter.RenameResult(0, 0, false, true);
        }
        return ModelKeyRewriter.rename(oldKey, newKey, projectRoot);
    }

    public static String warningMessage() {
        return "Renaming the catalog key rewrites every bin in this project that references this model. Continue?";
    }
}
