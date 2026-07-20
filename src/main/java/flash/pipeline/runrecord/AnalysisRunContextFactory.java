package flash.pipeline.runrecord;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;

/**
 * Resolves the current {@link ProjectFile} for an analysis directory.
 *
 * <p>Reads {@code project.json} from the canonical settings directory. Legacy
 * folder layouts that predate the project file return {@code null}, which
 * {@link AnalysisRunContext#open} handles gracefully (empty project hash; inputs
 * come from whatever the analysis itself enumerates).
 */
public final class AnalysisRunContextFactory {

    private AnalysisRunContextFactory() {
    }

    public static ProjectFile currentProjectFor(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            return null;
        }
        try {
            return ProjectFileIO.read(FlashProjectLayout.forDirectory(directory).configurationWriteDir());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
