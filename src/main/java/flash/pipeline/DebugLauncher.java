package flash.pipeline;

import ij.IJ;
import ij.ImageJ;

/**
 * Convenience launcher for running the plugin from Eclipse as a Java Application.
 */
public class DebugLauncher {
    public static void main(String[] args) {
        new ImageJ();
        IJ.runPlugIn(FLASH_Pipeline.class.getName(), "");
    }
}
