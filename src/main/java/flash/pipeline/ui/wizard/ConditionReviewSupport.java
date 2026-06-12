package flash.pipeline.ui.wizard;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.naming.ConditionNameParser;

import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared condition-only review foundation used by every entry point that needs
 * to answer "are conditions safe to use?" and to let the user fix them without
 * opening the full project setup editor.
 *
 * <p>This deliberately edits only animal -&gt; condition grouping through
 * {@link ConditionManifestIO} / {@code Conditions.csv}. Animal ID, hemisphere,
 * region, source files, and series selection stay the responsibility of the
 * project setup editor because those edits can invalidate prior analysis runs.
 *
 * <p>{@link #evaluate(String, java.util.Set)} is a no-create health check: it
 * reads {@code Conditions.csv} only if it already exists and never triggers the
 * auto-generation path, so banners can report status without side effects.
 */
public final class ConditionReviewSupport {

    private ConditionReviewSupport() {}

    public enum Severity {
        OK,
        INFO,
        WARNING,
        BLOCKING
    }

    /**
     * Immutable snapshot of how usable the current condition assignments are for
     * a given set of animals.
     */
    public static final class Health {
        public final Severity severity;
        public final int animalCount;
        public final int explicitCount;
        public final int autoDetectedCount;
        public final int blankCount;
        public final int conditionCount;
        public final List<String> messages;
        public final LinkedHashMap<String, String> resolvedAssignments;

        Health(Severity severity,
               int animalCount,
               int explicitCount,
               int autoDetectedCount,
               int blankCount,
               int conditionCount,
               List<String> messages,
               LinkedHashMap<String, String> resolvedAssignments) {
            this.severity = severity;
            this.animalCount = animalCount;
            this.explicitCount = explicitCount;
            this.autoDetectedCount = autoDetectedCount;
            this.blankCount = blankCount;
            this.conditionCount = conditionCount;
            this.messages = messages;
            this.resolvedAssignments = resolvedAssignments;
        }

        public boolean needsReview() {
            return severity == Severity.WARNING || severity == Severity.BLOCKING;
        }

        public boolean isBlocking() {
            return severity == Severity.BLOCKING;
        }

        /** Compact one-line status suitable for a picker/diagnostics banner. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(explicitCount).append('/').append(animalCount).append(" explicit");
            if (autoDetectedCount > 0) {
                sb.append(", ").append(autoDetectedCount).append(" auto-detected");
            }
            if (blankCount > 0) {
                sb.append(", ").append(blankCount).append(" unassigned");
            }
            sb.append(", ").append(conditionCount)
              .append(conditionCount == 1 ? " condition" : " conditions");
            if (needsReview()) {
                sb.append(" — needs review");
            }
            return sb.toString();
        }
    }

    /** Caller-specific labels/requirements for the condition-only review dialog. */
    public static final class Options {
        public String title = "Condition Assignment";
        public String primaryButtonText = "Save conditions";
        public String introHtml;
        public String[] workflowSteps;
        public int workflowActiveIndex = -1;
        public boolean requireAtLeastTwoConditions;
    }

    public static Health evaluate(String directory, Set<String> animals) {
        return evaluate(directory, animals, false);
    }

    /**
     * Classify condition assignments without creating {@code Conditions.csv}.
     *
     * @param requireAtLeastTwoConditions when {@code true}, blank assignments and
     *        fewer than two distinct conditions escalate to {@link Severity#BLOCKING}
     *        (statistics needs real groups); otherwise they are {@link Severity#WARNING}.
     */
    public static Health evaluate(String directory, Set<String> animals,
                                  boolean requireAtLeastTwoConditions) {
        LinkedHashMap<String, String> persisted =
                ConditionManifestIO.readAssignmentsIfExists(directory);

        LinkedHashMap<String, String> resolved = new LinkedHashMap<String, String>();
        LinkedHashSet<String> conditions = new LinkedHashSet<String>();
        int animalCount = 0;
        int explicit = 0;
        int autoDetected = 0;
        int blank = 0;

        if (animals != null) {
            for (String animal : animals) {
                if (animal == null) continue;
                animalCount++;
                String saved = persisted.get(animal);
                if (saved != null && !saved.trim().isEmpty()) {
                    String value = saved.trim();
                    resolved.put(animal, value);
                    conditions.add(value);
                    explicit++;
                } else {
                    String detected = ConditionNameParser.detectCondition(animal);
                    detected = detected == null ? "" : detected.trim();
                    resolved.put(animal, detected);
                    if (detected.isEmpty()) {
                        blank++;
                    } else {
                        conditions.add(detected);
                        autoDetected++;
                    }
                }
            }
        }

        int conditionCount = conditions.size();
        List<String> messages = new ArrayList<String>();
        Severity severity = Severity.OK;

        if (animalCount == 0) {
            severity = Severity.BLOCKING;
            messages.add("No animals found to assign.");
            return new Health(severity, 0, 0, 0, 0, 0, messages, resolved);
        }

        if (blank > 0) {
            severity = escalate(severity,
                    requireAtLeastTwoConditions ? Severity.BLOCKING : Severity.WARNING);
            messages.add(blank + (blank == 1 ? " animal" : " animals")
                    + " could not be assigned a condition.");
        }
        if (conditionCount <= 1) {
            severity = escalate(severity,
                    requireAtLeastTwoConditions ? Severity.BLOCKING : Severity.WARNING);
            messages.add("Only " + conditionCount
                    + (conditionCount == 1 ? " condition is" : " conditions are")
                    + " present; condition grouping will collapse.");
        }
        if (autoDetected > 0) {
            severity = escalate(severity, Severity.WARNING);
            messages.add(autoDetected + (autoDetected == 1 ? " animal" : " animals")
                    + " auto-detected from filenames; confirm before grouping.");
        }

        return new Health(severity, animalCount, explicit, autoDetected, blank,
                conditionCount, messages, resolved);
    }

    private static Severity escalate(Severity current, Severity candidate) {
        return candidate.ordinal() > current.ordinal() ? candidate : current;
    }

    /**
     * Show the condition-only review dialog prefilled with the current effective
     * assignments and persist accepted edits through {@link ConditionManifestIO}.
     *
     * @return the saved assignments, or {@code null} if the user cancelled or no
     *         animals were available to review.
     */
    public static LinkedHashMap<String, String> reviewAndSave(java.awt.Window owner,
                                                              String directory,
                                                              Set<String> animals,
                                                              Options options) {
        Options opt = options == null ? new Options() : options;
        if (animals == null || animals.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, String> prefill = evaluate(directory, animals).resolvedAssignments;
        return ConditionManifestPanel.showDialog(
                (Component) owner, directory, animals, prefill,
                opt.title, opt.primaryButtonText, opt.introHtml,
                opt.workflowSteps, opt.workflowActiveIndex);
    }
}
