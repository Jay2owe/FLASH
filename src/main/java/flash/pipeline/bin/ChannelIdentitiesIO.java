package flash.pipeline.bin;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.wizard.JsonIO;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes channel_identities.json in the active dataset configuration folder.
 */
public final class ChannelIdentitiesIO {

    public static final String FILE_NAME = "channel_identities.json";

    private ChannelIdentitiesIO() {
    }

    public static ChannelIdentities read(File binDir) {
        File file = readFile(binDir);
        if (file == null || !file.isFile()) {
            return new ChannelIdentities(null);
        }
        try {
            File safeFile = guardFile(file.getParentFile(), file);
            String json = new String(Files.readAllBytes(safeFile.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonIO.parseObject(json);
            List<ChannelIdentities.Entry> entries = new ArrayList<ChannelIdentities.Entry>();
            for (Object item : JsonIO.asList(root.get("channels"))) {
                Map<String, Object> row = JsonIO.asObject(item);
                entries.add(new ChannelIdentities.Entry(
                        JsonIO.intValue(row.get("channelIndex"), -1),
                        JsonIO.stringValue(row.get("markerId")),
                        JsonIO.stringValue(row.get("shape")),
                        JsonIO.booleanValue(row.get("crowdingSensitive"), false)));
            }
            return new ChannelIdentities(entries);
        } catch (Exception e) {
            IJ.log("WARNING: Could not read channel identities from "
                    + file.getAbsolutePath() + ": " + e.getMessage());
            return new ChannelIdentities(null);
        }
    }

    public static void write(File binDir, ChannelIdentities identities) throws IOException {
        if (binDir == null) {
            throw new IllegalArgumentException("binDir is required.");
        }
        File writeDir = writeDir(binDir);
        if (!writeDir.isDirectory() && !writeDir.mkdirs() && !writeDir.isDirectory()) {
            throw new IOException("Could not create " + writeDir.getAbsolutePath());
        }
        File target = file(writeDir);
        guardFile(writeDir, target);
        File temp = File.createTempFile("channel_identities-", ".tmp", writeDir);
        boolean moved = false;
        try {
            Files.write(temp.toPath(), JsonIO.write(toJsonObject(identities)).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    static Map<String, Object> toJsonObject(ChannelIdentities identities) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        List<Object> rows = new ArrayList<Object>();
        if (identities != null) {
            for (ChannelIdentities.Entry entry : identities.getEntries()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("channelIndex", Integer.valueOf(entry.getChannelIndex()));
                row.put("markerId", entry.getMarkerId());
                row.put("shape", entry.getShape());
                row.put("crowdingSensitive", Boolean.valueOf(entry.isCrowdingSensitive()));
                rows.add(row);
            }
        }
        root.put("channels", rows);
        return root;
    }

    private static File file(File binDir) {
        return binDir == null ? null : new File(binDir, FILE_NAME);
    }

    private static File readFile(File dir) {
        if (dir == null) return null;
        if (FlashProjectLayout.LEGACY_BIN_DIR.equals(dir.getName())) {
            File projectRoot = dir.getParentFile();
            if (projectRoot != null) {
                FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
                File newFile = file(layout.configurationWriteDir());
                if (newFile.isFile()) return newFile;
            }
        }
        return file(dir);
    }

    private static File writeDir(File dir) {
        if (FlashProjectLayout.LEGACY_BIN_DIR.equals(dir.getName())) {
            File projectRoot = dir.getParentFile();
            if (projectRoot != null) {
                return FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath()).configurationWriteDir();
            }
        }
        return dir;
    }

    private static File guardFile(File dir, File file) throws IOException {
        if (dir == null || file == null) {
            throw new IOException("Channel identities path is not configured.");
        }
        File container = dir.getCanonicalFile();
        File target = file.getCanonicalFile();
        String dirPath = container.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.equals(dirPath) && !targetPath.startsWith(dirPath + File.separator)) {
            throw new IOException("Channel identities path escapes configuration directory: " + file.getPath());
        }
        return target;
    }
}
