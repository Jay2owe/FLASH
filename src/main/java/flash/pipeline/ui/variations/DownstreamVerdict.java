package flash.pipeline.ui.variations;

import ij.ImagePlus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class DownstreamVerdict {

    private static final int CELL_DELTA_THRESHOLD = 5;
    private static final double PERCENT_CHANGE_THRESHOLD = 10.0d;

    private DownstreamVerdict() {
    }

    public static Map<ParameterCombo, Verdict> compute(
            List<VariationResult> filtered,
            DownstreamSegmenter segmenter,
            ImagePlus baselineCrop,
            VariationCache cache,
            BooleanSupplier cancelCheck,
            Consumer<Progress> progress) {
        if (segmenter == null) {
            throw new IllegalArgumentException("segmenter must not be null");
        }
        if (baselineCrop == null) {
            throw new IllegalArgumentException("baselineCrop must not be null");
        }
        Map<ParameterCombo, Verdict> verdicts =
                new LinkedHashMap<ParameterCombo, Verdict>();
        int total = filtered == null ? 0 : filtered.size();
        if (isCancelled(cancelCheck)) {
            return verdicts;
        }

        int baselineCount = runCount(segmenter, baselineCrop, null,
                segmenter.baselineSourceKey(), cache, cancelCheck);
        publish(progress, new Progress(0, total, baselineCount, null, null,
                "Downstream: baseline " + baselineCount + " cells"));

        for (int i = 0; filtered != null && i < filtered.size(); i++) {
            if (isCancelled(cancelCheck)) {
                return verdicts;
            }
            VariationResult result = filtered.get(i);
            if (result == null || result.hasError()) {
                publish(progress, new Progress(i + 1, total, baselineCount,
                        result == null ? null : result.combo(), null,
                        status(i + 1, total)));
                continue;
            }
            ImagePlus image = result.previewImage() == null
                    ? result.label()
                    : result.previewImage();
            if (image == null) {
                publish(progress, new Progress(i + 1, total, baselineCount,
                        result.combo(), null, status(i + 1, total)));
                continue;
            }

            String sourceKey = segmenter.filteredSourceKey(result.combo());
            int count;
            try {
                count = runCount(segmenter, image, result.combo(), sourceKey,
                        cache, cancelCheck);
            } catch (RuntimeException e) {
                publish(progress, new Progress(i + 1, total, baselineCount,
                        result.combo(), null,
                        status(i + 1, total) + " (skipped: "
                                + safe(e.getMessage()) + ")"));
                continue;
            }
            if (isCancelled(cancelCheck)) {
                return verdicts;
            }
            Verdict verdict = verdictFor(count, baselineCount);
            verdicts.put(result.combo(), verdict);
            publish(progress, new Progress(i + 1, total, baselineCount,
                    result.combo(), verdict, status(i + 1, total)));
        }
        return verdicts;
    }

    private static int runCount(DownstreamSegmenter segmenter,
                                ImagePlus image,
                                ParameterCombo upstreamCombo,
                                String filteredSourceKey,
                                VariationCache cache,
                                BooleanSupplier cancelCheck) {
        if (isCancelled(cancelCheck)) {
            return 0;
        }
        try {
            return segmenter.count(image, upstreamCombo, filteredSourceKey,
                    cache, cancelCheck);
        } catch (Exception e) {
            throw new IllegalStateException("Downstream segmentation failed: "
                    + safe(e.getMessage()), e);
        }
    }

    static Verdict verdictFor(int count, int baselineCount) {
        int delta = count - baselineCount;
        double percentChange;
        if (baselineCount == 0) {
            percentChange = delta == 0 ? 0.0d : (delta > 0 ? 100.0d : -100.0d);
        } else {
            percentChange = (delta * 100.0d) / baselineCount;
        }
        boolean largeEnough = Math.abs(percentChange) >= PERCENT_CHANGE_THRESHOLD;
        boolean help = delta >= CELL_DELTA_THRESHOLD && largeEnough;
        boolean hurt = delta <= -CELL_DELTA_THRESHOLD && largeEnough;
        return new Verdict(delta, percentChange, help, hurt);
    }

    private static void publish(Consumer<Progress> progress, Progress value) {
        if (progress != null) {
            progress.accept(value);
        }
    }

    private static boolean isCancelled(BooleanSupplier cancelCheck) {
        return cancelCheck != null && cancelCheck.getAsBoolean();
    }

    private static String status(int done, int total) {
        return "Downstream: " + done + " / " + total + " tiles";
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty()
                ? "unknown error"
                : value.trim();
    }

    public static final class Verdict {
        public final int deltaCells;
        public final double percentChange;
        public final boolean isHelp;
        public final boolean isHurt;

        public Verdict(int deltaCells,
                       double percentChange,
                       boolean isHelp,
                       boolean isHurt) {
            this.deltaCells = deltaCells;
            this.percentChange = percentChange;
            this.isHelp = isHelp;
            this.isHurt = isHurt;
        }
    }

    public static final class Progress {
        public final int completed;
        public final int total;
        public final int baselineCount;
        public final ParameterCombo combo;
        public final Verdict verdict;
        public final String message;

        public Progress(int completed,
                        int total,
                        int baselineCount,
                        ParameterCombo combo,
                        Verdict verdict,
                        String message) {
            this.completed = completed;
            this.total = total;
            this.baselineCount = baselineCount;
            this.combo = combo;
            this.verdict = verdict;
            this.message = message == null ? "" : message;
        }
    }
}
