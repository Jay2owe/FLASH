package flash.pipeline.image.variation;

import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.ParallelContext;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagRejectedException;
import ij.ImagePlus;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes variant plans against duplicate sources and captures per-plan
 * failures without aborting the batch.
 */
public final class VariantExecutor {

    static final int MAX_NATIVE_WORKERS = 2;

    private static final ExecutorBackend DEFAULT_BACKEND = new ExecutorBackend() {
        @Override
        public ImagePlus runNative(ImagePlus source, DagIR dag) throws Exception {
            return FilterExecutor.runDagThreadSafe(source, dag);
        }

        @Override
        public ImagePlus runLegacy(ImagePlus source, DagIR dag) throws Exception {
            return FilterExecutor.runLegacyDagSandboxed(source, dag);
        }
    };

    private static ExecutorBackend backend = DEFAULT_BACKEND;

    private VariantExecutor() {}

    public static List<VariantResult> runAll(ImagePlus source,
                                             List<VariantPlan> plans,
                                             ProgressCallback progress) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (plans == null) throw new IllegalArgumentException("plans must not be null");
        validatePlans(plans);

        int total = plans.size();
        notifyStart(progress, total);
        if (total == 0) {
            List<VariantResult> empty = new ArrayList<VariantResult>();
            notifyAllDone(progress, empty);
            return empty;
        }

        List<VariantResult> results = containsLegacy(plans)
                ? runSerial(source, plans, progress)
                : runParallel(source, plans, progress);
        notifyAllDone(progress, results);
        return results;
    }

    private static boolean containsLegacy(List<VariantPlan> plans) {
        for (int i = 0; i < plans.size(); i++) {
            VariantPlan plan = plans.get(i);
            if (plan != null && "legacy".equals(plan.dag.executionTier)) {
                return true;
            }
        }
        return false;
    }

    private static void validatePlans(List<VariantPlan> plans) {
        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i) == null) {
                throw new IllegalArgumentException("plans must not contain null entries");
            }
        }
    }

    private static List<VariantResult> runSerial(ImagePlus source,
                                                 List<VariantPlan> plans,
                                                 ProgressCallback progress) {
        int total = plans.size();
        List<VariantResult> out = new ArrayList<VariantResult>(total);
        for (int i = 0; i < total; i++) {
            VariantResult result = runOne(source, plans.get(i));
            out.add(result);
            notifyComplete(progress, i + 1, total, result);
        }
        return out;
    }

    private static List<VariantResult> runParallel(ImagePlus source,
                                                   List<VariantPlan> plans,
                                                   ProgressCallback progress) {
        int total = plans.size();
        ExecutorService exec = Executors.newFixedThreadPool(nativeWorkerCount(total));
        try {
            List<Future<VariantResult>> futures =
                    new ArrayList<Future<VariantResult>>(total);
            for (int i = 0; i < total; i++) {
                final VariantPlan plan = plans.get(i);
                futures.add(exec.submit(new Callable<VariantResult>() {
                    @Override
                    public VariantResult call() {
                        return runOne(source, plan);
                    }
                }));
            }

            List<VariantResult> out = new ArrayList<VariantResult>(total);
            for (int i = 0; i < total; i++) {
                VariantResult result;
                try {
                    result = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = new VariantResult(plans.get(i), null, e, 0L);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    result = new VariantResult(plans.get(i), null, cause, 0L);
                }
                out.add(result);
                notifyComplete(progress, i + 1, total, result);
            }
            return out;
        } finally {
            exec.shutdown();
        }
    }

    private static VariantResult runOne(ImagePlus source, VariantPlan plan) {
        long start = System.currentTimeMillis();
        ImagePlus executionSource = null;
        boolean enteredParallel = false;
        try {
            executionSource = VariationSourcePreparer.duplicateWholeImage(source);
            ParallelContext.enterParallel();
            enteredParallel = true;

            ImagePlus output;
            if ("legacy".equals(plan.dag.executionTier)) {
                output = backend.runLegacy(executionSource, plan.dag);
            } else {
                output = backend.runNative(executionSource, plan.dag);
            }
            if (output == executionSource) {
                executionSource = null;
            }
            if (output == null) {
                throw new DagRejectedException("DAG produced no output");
            }
            return new VariantResult(plan, output, null,
                    System.currentTimeMillis() - start);
        } catch (Throwable t) {
            return new VariantResult(plan, null, t,
                    System.currentTimeMillis() - start);
        } finally {
            if (enteredParallel) {
                ParallelContext.exitParallel();
            }
            if (executionSource != null) {
                executionSource.flush();
            }
        }
    }

    static int nativeWorkerCount(int totalPlans) {
        if (totalPlans < 1) return 1;
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(totalPlans, Math.min(MAX_NATIVE_WORKERS, cores)));
    }

    static void setBackendForTests(ExecutorBackend newBackend) {
        backend = newBackend == null ? DEFAULT_BACKEND : newBackend;
    }

    interface ExecutorBackend {
        ImagePlus runNative(ImagePlus source, DagIR dag) throws Exception;
        ImagePlus runLegacy(ImagePlus source, DagIR dag) throws Exception;
    }

    private static void notifyStart(final ProgressCallback progress, final int total) {
        if (progress == null) return;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progress.onStart(total);
            }
        });
    }

    private static void notifyComplete(final ProgressCallback progress,
                                       final int completed,
                                       final int total,
                                       final VariantResult result) {
        if (progress == null) return;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progress.onVariantComplete(completed, total, result);
            }
        });
    }

    private static void notifyAllDone(final ProgressCallback progress,
                                      final List<VariantResult> results) {
        if (progress == null) return;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progress.onAllDone(results);
            }
        });
    }
}
