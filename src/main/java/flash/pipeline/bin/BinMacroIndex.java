package flash.pipeline.bin;

import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Shared index for saved custom filter preset macros.
 */
public final class BinMacroIndex {
    private static final ExecutorService SCAN_EXECUTOR =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "FLASH-bin-macro-index");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private static final Object LOCK = new Object();
    private static final Map<File, CompletableFuture<List<String>>> NAME_FUTURES =
            new HashMap<File, CompletableFuture<List<String>>>();

    private BinMacroIndex() {
    }

    public static File[] listSavedCustomFilterPresetFiles(File binFolder) {
        List<File> out = new ArrayList<File>();
        List<File> dirs = layoutForBinFolder(binFolder).customFilterPresetReadDirs();
        for (int i = 0; i < dirs.size(); i++) {
            File[] files = listIjmFiles(dirs.get(i));
            for (int f = 0; f < files.length; f++) {
                out.add(files[f]);
            }
        }
        return out.toArray(new File[out.size()]);
    }

    public static List<String> listSavedCustomFilterPresetNames(File binFolder) {
        List<String> names = new ArrayList<String>();
        File[] files = listSavedCustomFilterPresetFiles(binFolder);
        for (int i = 0; i < files.length; i++) {
            String fileName = files[i].getName();
            if (fileName == null || fileName.length() <= 4) continue;
            addUniqueOption(names, fileName.substring(0, fileName.length() - 4));
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return Collections.unmodifiableList(names);
    }

    public static CompletableFuture<List<String>> savedCustomFilterPresetNamesAsync(final File binFolder) {
        final File key = cacheKey(binFolder);
        synchronized (LOCK) {
            CompletableFuture<List<String>> existing = NAME_FUTURES.get(key);
            if (existing != null) {
                return existing;
            }
            CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(
                    new Supplier<List<String>>() {
                        @Override public List<String> get() {
                            return listSavedCustomFilterPresetNames(binFolder);
                        }
                    },
                    SCAN_EXECUTOR);
            NAME_FUTURES.put(key, future);
            future.whenComplete(new BiConsumer<List<String>, Throwable>() {
                @Override public void accept(List<String> result, Throwable error) {
                    if (error == null) return;
                    synchronized (LOCK) {
                        CompletableFuture<List<String>> current = NAME_FUTURES.get(key);
                        if (current == future) {
                            NAME_FUTURES.remove(key);
                        }
                    }
                }
            });
            return future;
        }
    }

    public static void invalidate(File binFolder) {
        synchronized (LOCK) {
            NAME_FUTURES.remove(cacheKey(binFolder));
        }
    }

    static void clearForTests() {
        synchronized (LOCK) {
            NAME_FUTURES.clear();
        }
    }

    private static File[] listIjmFiles(File dir) {
        if (dir == null) return new File[0];
        File[] files = dir.listFiles(new FilenameFilter() {
            @Override public boolean accept(File parent, String name) {
                return name != null && name.toLowerCase(Locale.ROOT).endsWith(".ijm");
            }
        });
        return files == null ? new File[0] : files;
    }

    private static void addUniqueOption(List<String> options, String value) {
        if (value == null || value.trim().length() == 0) return;
        String trimmed = value.trim();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(trimmed)) return;
        }
        options.add(trimmed);
    }

    private static File cacheKey(File binFolder) {
        return canonical(projectRootForBinFolder(binFolder));
    }

    private static File canonical(File file) {
        File target = file == null ? new File(".") : file;
        try {
            return target.getCanonicalFile();
        } catch (Exception e) {
            return target.getAbsoluteFile();
        }
    }

    private static FlashProjectLayout layoutForBinFolder(File binFolder) {
        return FlashProjectLayout.forDirectory(projectRootForBinFolder(binFolder).getPath());
    }

    private static File projectRootForBinFolder(File binFolder) {
        if (binFolder == null) return new File(".");
        File parent = binFolder.getParentFile();
        if (FlashProjectLayout.LEGACY_BIN_DIR.equals(binFolder.getName()) && parent != null) {
            return parent;
        }
        String folderName = binFolder.getName();
        if (FlashProjectLayout.SETTINGS_DIR.equals(folderName)
                && parent != null
                && FlashProjectLayout.CONFIGURATION_DIR.equals(parent.getName())
                && parent.getParentFile() != null
                && FlashProjectLayout.FLASH_DIR.equals(parent.getParentFile().getName())
                && parent.getParentFile().getParentFile() != null) {
            return parent.getParentFile().getParentFile();
        }
        if ((FlashProjectLayout.CONFIGURATION_DIR.equals(folderName)
                || FlashProjectLayout.LEGACY_CONFIGURATION_DIR.equals(folderName))
                && parent != null
                && FlashProjectLayout.FLASH_DIR.equals(parent.getName())
                && parent.getParentFile() != null) {
            return parent.getParentFile();
        }
        return binFolder;
    }
}
