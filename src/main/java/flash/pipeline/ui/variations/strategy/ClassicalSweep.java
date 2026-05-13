package flash.pipeline.ui.variations.strategy;

import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ClassicalSweep implements VariationStrategy {

    private final ImagePlus filteredSource;
    private final CropSpec crop;
    private final VariationCache cache;
    private final ClassicalSegmentationStage.PreviewAdapter previewAdapter;
    private final int parallelism;

    public ClassicalSweep(ImagePlus filteredSource,
                          CropSpec crop,
                          VariationCache cache,
                          ClassicalSegmentationStage.PreviewAdapter previewAdapter,
                          int parallelism) {
        if (filteredSource == null) {
            throw new IllegalArgumentException("filteredSource must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.filteredSource = filteredSource;
        this.crop = crop == null ? CropSpec.full() : crop;
        this.cache = cache;
        this.previewAdapter = previewAdapter;
        this.parallelism = effectiveParallelism(parallelism);
    }

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) throws Exception {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (sweep.method() != ParameterSweep.Method.CLASSICAL) {
            throw new IllegalArgumentException("ClassicalSweep only accepts Classical sweeps");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }
        CropSpec activeCrop = sweep.cropSpec() == null ? crop : sweep.cropSpec();
        final ImagePlus cropped = activeCrop.apply(filteredSource);
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);
            java.util.ArrayList<ForkJoinTask<?>> tasks =
                    new java.util.ArrayList<ForkJoinTask<?>>();
            for (int i = 0; i < ordered.size(); i++) {
                if (isCancelled(cancelCheck)) {
                    pool.shutdownNow();
                    return;
                }
                final ParameterCombo combo = ordered.get(i);
                final String cacheKey = VariationCache.keyFor(sweep, combo);
                ImagePlus cached = cache == null ? null : cache.get(cacheKey);
                if (cached != null) {
                    publisher.accept(VariationResult.success(combo, cached,
                            countLabels(cached), 0L, null));
                    continue;
                }
                tasks.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        runOne(cropped, combo, cacheKey, publisher, cancelCheck);
                    }
                }));
            }
            waitForTasks(tasks, pool, cancelCheck);
        } finally {
            try {
                pool.shutdown();
                if (!pool.awaitTermination(60L, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } finally {
                closeCroppedIfOwned(cropped);
            }
        }
    }

    private void runOne(ImagePlus cropped,
                        ParameterCombo combo,
                        String cacheKey,
                        Consumer<VariationResult> publisher,
                        BooleanSupplier cancelCheck) {
        if (isCancelled(cancelCheck)) {
            return;
        }
        long started = System.currentTimeMillis();
        try {
            int threshold = intParameter(combo, ParameterId.THRESHOLD, 0);
            int minSize = intParameter(combo, ParameterId.MIN_SIZE, 0);
            int maxSize = intParameter(combo, ParameterId.MAX_SIZE, Integer.MAX_VALUE);
            ObjectsCounter3DWrapper.Result preview =
                    previewAdapter.runPreview(cropped, threshold, minSize, maxSize);
            ImagePlus label = preview == null ? null : preview.getObjectsMap();
            ResultsTable stats = preview == null ? null : preview.getStatistics();
            int count = previewAdapter.countObjects(preview);
            if (label == null) {
                label = emptyLabelMapLike(cropped);
            }
            if (cache != null) {
                cache.put(cacheKey, label);
            }
            long durationMs = Math.max(1L, System.currentTimeMillis() - started);
            if (!isCancelled(cancelCheck)) {
                publisher.accept(VariationResult.success(combo, label, count, durationMs, stats));
            }
        } catch (Throwable t) {
            if (!isCancelled(cancelCheck)) {
                publisher.accept(VariationResult.failure(combo, t));
            }
        }
    }

    private static void waitForTasks(List<ForkJoinTask<?>> tasks,
                                     ForkJoinPool pool,
                                     BooleanSupplier cancelCheck) throws Exception {
        for (int i = 0; i < tasks.size(); i++) {
            ForkJoinTask<?> task = tasks.get(i);
            while (!task.isDone()) {
                if (isCancelled(cancelCheck)) {
                    pool.shutdownNow();
                    return;
                }
                try {
                    task.get(50L, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Keep polling so cancellation can interrupt the pool promptly.
                }
            }
            task.get();
        }
    }

    private void closeCroppedIfOwned(ImagePlus cropped) {
        if (cropped == null || cropped == filteredSource) {
            return;
        }
        previewAdapter.close(cropped);
    }

    private static boolean isCancelled(BooleanSupplier cancelCheck) {
        return cancelCheck != null && cancelCheck.getAsBoolean();
    }

    private static int effectiveParallelism(int requested) {
        int max = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        if (requested <= 0) {
            return max;
        }
        return Math.max(1, Math.min(requested, max));
    }

    private static int intParameter(ParameterCombo combo, ParameterId id, int fallback) {
        Object value = combo == null ? null : combo.get(id);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return Math.max(0, (int) Math.round(((Number) value).doubleValue()));
        }
        try {
            return Math.max(0, (int) Math.round(Double.parseDouble(String.valueOf(value))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static ImagePlus emptyLabelMapLike(ImagePlus reference) {
        int width = reference == null ? 1 : Math.max(1, reference.getWidth());
        int height = reference == null ? 1 : Math.max(1, reference.getHeight());
        int stackSize = reference == null ? 1 : Math.max(1, reference.getStackSize());
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < stackSize; i++) {
            stack.addSlice("z" + (i + 1), new ShortProcessor(width, height));
        }
        ImagePlus label = new ImagePlus("Object label preview (no objects)", stack);
        if (reference != null && reference.getCalibration() != null) {
            label.setCalibration(reference.getCalibration().copy());
        }
        if (reference != null) {
            int channels = Math.max(1, reference.getNChannels());
            int slices = Math.max(1, reference.getNSlices());
            int frames = Math.max(1, reference.getNFrames());
            if (channels * slices * frames == stackSize) {
                label.setDimensions(channels, slices, frames);
                label.setOpenAsHyperStack(reference.isHyperStack());
            }
        }
        return label;
    }

    private static int countLabels(ImagePlus label) {
        if (label == null || label.getStack() == null) {
            return 0;
        }
        Set<Integer> labels = new HashSet<Integer>();
        ImageStack stack = label.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int value = Math.round(processor.getf(i));
                if (value > 0) {
                    labels.add(Integer.valueOf(value));
                }
            }
        }
        return labels.size();
    }
}
