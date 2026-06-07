package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.recipes.PipelineRecipe;
import flash.pipeline.recipes.PipelineRecipeIO;
import loci.formats.ImageReader;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Folder-aware, rule-based recommendation logic for the main Help dialog.
 */
public final class AnalysisAdvisor {

    private static final long DIMENSION_SNIFF_TIMEOUT_MS = 4000L;
    private static final int MAX_DIMENSION_CACHE_ENTRIES = 64;
    private static final Map<String, DimensionSniff> DIMENSION_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, DimensionSniff>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DimensionSniff> eldest) {
                    return size() > MAX_DIMENSION_CACHE_ENTRIES;
                }
            });

    public AdvisorResult recommend(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return new AdvisorResult(
                    "Pick a directory first.",
                    "I need a project folder before I can recommend what to run.",
                    null,
                    new int[0]);
        }

        File firstImage = firstImageFile(directory);
        if (firstImage == null) {
            return new AdvisorResult(
                    "I don't see any images in this folder.",
                    "Pick a different directory.",
                    null,
                    new int[0]);
        }

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory.getAbsolutePath());
        File config = new File(layout.configurationWriteDir(), ChannelConfigIO.FILE_NAME);
        if (!config.exists()) {
            return new AdvisorResult(
                    "Run Set Up Configuration first.",
                    "FLASH needs to know what your channels mean before any analysis can run. Click the button below to tick Set Up Configuration. Once that's done, come back here and I'll suggest a full pipeline based on your data.",
                    null,
                    new int[] { FLASH_Pipeline.IDX_CREATE_BIN });
        }

        boolean hasROIs = hasRoiFiles(directory);
        int channelCount;
        try {
            channelCount = BinConfigIO.readFromDirectory(directory.getAbsolutePath()).numChannels();
        } catch (IOException e) {
            return new AdvisorResult(
                    "I can't confidently recommend a recipe.",
                    "I found a Configuration folder, but couldn't read channel_config.json. Re-run Set Up Configuration or pick analyses manually.",
                    null,
                    new int[0]);
        }

        DimensionSniff sniff = sniffFirstImageDimensions(directory, firstImage);
        if (!sniff.hasImage) {
            return new AdvisorResult(
                    "I don't see any images in this folder.",
                    "Pick a different directory.",
                    null,
                    new int[0]);
        }
        if (!sniff.confident) {
            return new AdvisorResult(
                    "What should I run?",
                    "I can't confidently recommend a recipe - your data shape is unusual. Pick analyses manually, or open Options for fine control.",
                    null,
                    new int[0]);
        }

        boolean has3DStacks = sniff.zSlices > 1;
        String recipeId = null;
        String reasoning;
        if (channelCount >= 4 && has3DStacks && !hasROIs) {
            recipeId = "standard-3d-intensity";
            reasoning = String.format(Locale.ROOT,
                    "You have %d-channel z-stacks and no ROIs yet. Recommended: draw ROIs, split & merge, run 3D Object Analysis with intensity, aggregate, and export Excel.",
                    Integer.valueOf(channelCount));
        } else if (channelCount >= 2 && has3DStacks && hasROIs) {
            recipeId = "standard-3d-intensity";
            reasoning = "You have z-stacks with ROIs already drawn. The Standard 3D + Intensity recipe will run end-to-end without re-drawing.";
        } else if (channelCount >= 2 && !has3DStacks) {
            recipeId = "quick-cell-count";
            reasoning = "Your images look 2D-ish. The Quick cell count recipe is the fastest path to a per-condition table.";
        } else {
            reasoning = "I can't confidently recommend a recipe - your data shape is unusual. Pick analyses manually, or open Options for fine control.";
        }

        int[] indices = recipeId == null ? new int[0] : loadRecipeIndices(recipeId);
        return new AdvisorResult("What should I run?", reasoning, recipeId, indices);
    }

    private static int[] loadRecipeIndices(String recipeId) {
        try {
            PipelineRecipe recipe = PipelineRecipeIO.loadFromResources(recipeId);
            return toIndices(recipe.getAnalyses());
        } catch (IOException e) {
            return new int[0];
        }
    }

    private static int[] toIndices(List<String> analysisKeys) {
        if (analysisKeys == null || analysisKeys.isEmpty()) {
            return new int[0];
        }
        int[] tmp = new int[analysisKeys.size()];
        int count = 0;
        for (String key : analysisKeys) {
            Integer idx = PipelineRecipe.KEY_TO_IDX.get(key);
            if (idx != null) {
                tmp[count++] = idx.intValue();
            }
        }
        int[] out = new int[count];
        System.arraycopy(tmp, 0, out, 0, count);
        return out;
    }

    private static File firstImageFile(File directory) {
        if (directory != null && ImageSourceDispatcher.hasProjectManifest(directory.getAbsolutePath())) {
            try {
                List<File> projectContainers = ImageSourceDispatcher.projectContainerFiles(directory.getAbsolutePath());
                if (!projectContainers.isEmpty()) {
                    return projectContainers.get(0);
                }
                List<File> projectTiffs = ImageSourceDispatcher.projectTiffFiles(directory.getAbsolutePath());
                if (!projectTiffs.isEmpty()) {
                    return projectTiffs.get(0);
                }
            } catch (RuntimeException e) {
                return null;
            }
            return null;
        }

        List<File> containers = ImageSourceDispatcher.listContainers(directory);
        if (!containers.isEmpty()) {
            return containers.get(0);
        }

        File input = new File(directory, "input");
        List<File> inputTiffs = ImageSourceDispatcher.listTiffs(input);
        if (!inputTiffs.isEmpty()) {
            return inputTiffs.get(0);
        }

        List<File> looseTiffs = ImageSourceDispatcher.listTiffs(directory);
        if (!looseTiffs.isEmpty()) {
            return looseTiffs.get(0);
        }
        return null;
    }

    private static boolean hasRoiFiles(File directory) {
        return hasRoiFiles(directory, 0);
    }

    private static boolean hasRoiFiles(File file, int depth) {
        if (file == null || depth > 8) {
            return false;
        }
        if (file.isFile()) {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            return lower.endsWith(".roi")
                    || (lower.endsWith(".zip") && lower.indexOf("roiset") >= 0);
        }
        File[] children = file.listFiles();
        if (children == null) {
            return false;
        }
        for (int i = 0; i < children.length; i++) {
            if (hasRoiFiles(children[i], depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static DimensionSniff sniffFirstImageDimensions(final File directory, final File imageFile) {
        String key = dimensionCacheKey(directory, imageFile);
        DimensionSniff cached = DIMENSION_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "flash-help-dimension-sniff");
                t.setDaemon(true);
                return t;
            }
        });
        Future<DimensionSniff> future = executor.submit(new Callable<DimensionSniff>() {
            @Override public DimensionSniff call() {
                return readDimensions(directory, imageFile);
            }
        });

        DimensionSniff result;
        try {
            result = future.get(DIMENSION_SNIFF_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            result = DimensionSniff.uncertain();
        } finally {
            executor.shutdownNow();
        }
        DIMENSION_CACHE.put(key, result);
        return result;
    }

    private static String dimensionCacheKey(File directory, File imageFile) {
        StringBuilder sb = new StringBuilder();
        if (directory != null) {
            sb.append(directory.getAbsolutePath())
                    .append('|').append(directory.lastModified());
            File projectJson = new File(
                    FlashProjectLayout.forDirectory(directory.getAbsolutePath()).configurationWriteDir(),
                    ProjectFileIO.FILE_NAME);
            if (projectJson.isFile()) {
                sb.append("|project=")
                        .append(projectJson.lastModified())
                        .append(':')
                        .append(projectJson.length());
            }
        }
        if (imageFile != null) {
            sb.append("|image=")
                    .append(imageFile.getAbsolutePath())
                    .append(':')
                    .append(imageFile.lastModified())
                    .append(':')
                    .append(imageFile.length());
        }
        return sb.toString();
    }

    private static DimensionSniff readDimensions(File directory, File imageFile) {
        if (directory != null && ImageSourceDispatcher.hasProjectManifest(directory.getAbsolutePath())) {
            return readProjectDimensions(directory);
        }
        return readFileDimensions(imageFile);
    }

    private static DimensionSniff readProjectDimensions(File directory) {
        try {
            List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory.getAbsolutePath());
            if (metas != null) {
                for (SeriesMeta meta : metas) {
                    if (meta != null && !ImageNameParser.isPreviewSeriesName(meta.name)) {
                        return DimensionSniff.confident(meta.nSlices);
                    }
                }
            }
            return DimensionSniff.noImage();
        } catch (Throwable t) {
            return DimensionSniff.uncertain();
        }
    }

    private static DimensionSniff readFileDimensions(File imageFile) {
        if (imageFile == null || !imageFile.isFile()) {
            return DimensionSniff.noImage();
        }
        ImageReader reader = null;
        try {
            reader = new ImageReader();
            reader.setId(imageFile.getAbsolutePath());
            return DimensionSniff.confident(reader.getSizeZ());
        } catch (Throwable t) {
            return DimensionSniff.uncertain();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class DimensionSniff {
        private final boolean hasImage;
        private final boolean confident;
        private final int zSlices;

        private DimensionSniff(boolean hasImage, boolean confident, int zSlices) {
            this.hasImage = hasImage;
            this.confident = confident;
            this.zSlices = zSlices;
        }

        private static DimensionSniff noImage() {
            return new DimensionSniff(false, false, 0);
        }

        private static DimensionSniff uncertain() {
            return new DimensionSniff(true, false, 0);
        }

        private static DimensionSniff confident(int zSlices) {
            return new DimensionSniff(true, true, Math.max(1, zSlices));
        }
    }
}
