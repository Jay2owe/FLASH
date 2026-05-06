package flash.pipeline.io;

import ij.IJ;
import flash.pipeline.ui.PipelineDialog;

import javax.swing.JButton;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Offers a one-time migration from loose project-root TIFF files into input/.
 */
public final class LooseTiffRelocator {

    public static final String NO_PROMPT_MARKER = ".ihf-no-input-folder";

    public enum Choice { MOVED, LEAVE_LOOSE, CANCELLED }

    private LooseTiffRelocator() {}

    public static boolean shouldPrompt(String directory) {
        for (File marker : FlashProjectLayout.forDirectory(directory).statusReadFiles(NO_PROMPT_MARKER)) {
            if (marker.isFile()) return false;
        }
        return true;
    }

    public static Choice promptAndMaybeMove(String directory, List<File> looseTiffs) {
        if (isHeadless()) return Choice.LEAVE_LOOSE;
        if (looseTiffs == null || looseTiffs.isEmpty()) return Choice.CANCELLED;

        final PipelineDialog dialog = new PipelineDialog("Move TIFFs to input/ folder?");
        dialog.setDefaultButtonsVisible(false);
        dialog.addMessage("Found " + looseTiffs.size()
                + " TIFF files in the project root.<br>"
                + "The pipeline expects raw input in an 'input/' subfolder.<br>"
                + "Move them now?");

        JButton moveButton = dialog.addFooterButton("Move");
        JButton leaveButton = dialog.addFooterButton("Leave loose");
        JButton cancelButton = dialog.addFooterButton("Cancel");
        moveButton.addActionListener(e -> dialog.closeWithAction("move"));
        leaveButton.addActionListener(e -> dialog.closeWithAction("leave_loose"));
        cancelButton.addActionListener(e -> dialog.closeWithAction("cancel"));

        dialog.showDialog();
        String action = dialog.getActionCommand();
        if ("move".equals(action)) {
            int moved = moveAll(directory, looseTiffs);
            IJ.log("IHF Pipeline: moved " + moved + "/" + looseTiffs.size()
                    + " loose TIFF files into input/.");
            return Choice.MOVED;
        }
        if ("leave_loose".equals(action)) {
            writeMarker(directory);
            return Choice.LEAVE_LOOSE;
        }
        return Choice.CANCELLED;
    }

    static int moveAll(String directory, List<File> tiffs) {
        if (directory == null || tiffs == null || tiffs.isEmpty()) return 0;

        File inputDir = new File(directory, "input");
        if (!inputDir.isDirectory() && !inputDir.mkdirs()) {
            IJ.log("IHF Pipeline: failed to create input/ directory: "
                    + inputDir.getAbsolutePath());
            return 0;
        }

        int moved = 0;
        for (File src : tiffs) {
            if (src == null) continue;
            File dst = new File(inputDir, src.getName());
            if (dst.exists()) {
                IJ.log("IHF Pipeline: skipping loose TIFF because input/ target exists: "
                        + dst.getAbsolutePath());
                continue;
            }
            if (!src.isFile()) {
                IJ.log("IHF Pipeline: skipping missing loose TIFF: "
                        + src.getAbsolutePath());
                continue;
            }
            if (moveOne(src, dst)) moved++;
        }
        return moved;
    }

    static boolean isHeadless() {
        try {
            Method method = IJ.class.getMethod("isHeadless");
            Object value = method.invoke(null);
            if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        } catch (Exception ignored) {
            // Older ImageJ versions do not expose IJ.isHeadless().
        }
        return GraphicsEnvironment.isHeadless();
    }

    private static boolean moveOne(File src, File dst) {
        try {
            Files.move(src.toPath(), dst.toPath());
            return true;
        } catch (IOException moveError) {
            try {
                Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.delete(src.toPath());
                IJ.log("IHF Pipeline: copied then deleted loose TIFF after move failed: "
                        + src.getName());
                return true;
            } catch (IOException copyError) {
                IJ.log("IHF Pipeline: failed to move loose TIFF " + src.getAbsolutePath()
                        + " to " + dst.getAbsolutePath() + ": "
                        + copyError.getMessage());
                return false;
            }
        }
    }

    private static void writeMarker(String directory) {
        File marker = FlashProjectLayout.forDirectory(directory).statusWriteFile(NO_PROMPT_MARKER);
        File parent = marker.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            IJ.log("IHF Pipeline: failed to create status marker folder: "
                    + parent.getAbsolutePath());
            return;
        }
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(marker), StandardCharsets.UTF_8))) {
            writer.println("# IHF Pipeline: do not prompt to move loose TIFFs in this directory.");
        } catch (IOException e) {
            IJ.log("IHF Pipeline: failed to write loose-TIFF prompt marker: "
                    + marker.getAbsolutePath());
        }
    }
}
