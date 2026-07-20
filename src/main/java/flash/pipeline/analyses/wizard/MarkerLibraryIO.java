package flash.pipeline.analyses.wizard;

import flash.pipeline.marker.MarkerLibrary;

import java.io.IOException;

/**
 * Loader facade for the bundled channel marker library.
 */
public final class MarkerLibraryIO {

    private MarkerLibraryIO() {
    }

    public static MarkerLibrary loadBundled() throws IOException {
        return MarkerLibrary.loadBundled();
    }
}
