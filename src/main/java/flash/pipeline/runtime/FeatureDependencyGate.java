package flash.pipeline.runtime;

import flash.pipeline.ui.PipelineDialog;
import ij.IJ;

import javax.swing.JButton;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared lazy gate for optional feature dependencies.
 */
public final class FeatureDependencyGate {

    public interface DependenciesDialogOpener {
        void openDependenciesDialog();
    }

    public enum GateDecision {
        ALLOWED(true, false),
        BLOCKED(false, false),
        CHANGE_SETUP(false, true);

        private final boolean allowed;
        private final boolean changeSetupRequested;

        GateDecision(boolean allowed, boolean changeSetupRequested) {
            this.allowed = allowed;
            this.changeSetupRequested = changeSetupRequested;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public boolean isChangeSetupRequested() {
            return changeSetupRequested;
        }
    }

    interface UiHooks {
        boolean isHeadless();

        void log(String message);

        void showMessage(String title, String message);

        String showMissingDependencyDialog(String analysis,
                                           String requirement,
                                           DependencySpec spec,
                                           DependencyStatus status,
                                           String plainMessage,
                                           List<GateAction> actions);
    }

    static final class GateAction {
        private final String command;
        private final String label;

        GateAction(String command, String label) {
            this.command = command == null ? "" : command;
            this.label = label == null ? "" : label;
        }

        String getCommand() {
            return command;
        }

        String getLabel() {
            return label;
        }
    }

    private static final Object LOCK = new Object();
    private static final UiHooks DEFAULT_UI_HOOKS = new SwingUiHooks();
    private static final String ACTION_OPEN_DEPENDENCIES = "open_dependencies";
    private static final String ACTION_CHANGE_SETUP = "change_setup";
    private static final String ACTION_FIX_PREFIX = "fix:";

    private static DependencyService dependencyService = new DependencyService();
    private static DependenciesDialogOpener dependenciesDialogOpener;
    private static boolean headlessMode = false;
    private static UiHooks uiHooks = DEFAULT_UI_HOOKS;

    private FeatureDependencyGate() {}

    public static void configure(DependencyService service, DependenciesDialogOpener opener) {
        synchronized (LOCK) {
            if (service != null) {
                dependencyService = service;
            }
            dependenciesDialogOpener = opener;
        }
    }

    public static void setUiMode(boolean headless) {
        synchronized (LOCK) {
            headlessMode = headless;
        }
    }

    /*
     * Package-private test seam: lets unit tests verify gate UI decisions
     * without constructing Swing dialogs in a headless test JVM.
     */
    static void setUiHooksForTesting(UiHooks hooks) {
        synchronized (LOCK) {
            uiHooks = hooks == null ? DEFAULT_UI_HOOKS : hooks;
        }
    }

    public static boolean gate(DependencyId id, String featureDisplayName) {
        return check(id, featureDisplayName, "").isAllowed();
    }

    public static boolean gate(DependencyId id, String analysisDisplayName, String requirementDisplayName) {
        return check(id, analysisDisplayName, requirementDisplayName).isAllowed();
    }

    public static GateDecision check(DependencyId id, String analysisDisplayName, String requirementDisplayName) {
        DependencySpec spec = DependencyRegistry.get(id);
        String analysis = normalizeAnalysisName(analysisDisplayName);
        String requirement = normalizeRequirementName(requirementDisplayName, analysis);
        if (spec == null) {
            currentUiHooks().log("Dependency gate failed: unknown dependency id " + id + " for " + analysis + ".");
            return GateDecision.BLOCKED;
        }

        DependencyService service = currentDependencyService();
        DependencyStatus status = service.getStatus(id);
        if (status != null && status.isPresent()) {
            return GateDecision.ALLOWED;
        }

        String plainMessage = buildPlainMessage(spec, status, analysis, requirement);
        if (isHeadless()) {
            currentUiHooks().log("Blocked analysis: " + analysis);
            if (!requirement.isEmpty()) {
                currentUiHooks().log("Required for: " + requirement);
            }
            currentUiHooks().log(plainMessage);
            return GateDecision.BLOCKED;
        }

        String action = currentUiHooks().showMissingDependencyDialog(
                analysis,
                requirement,
                spec,
                status,
                plainMessage,
                buildGateActions(spec));
        if (ACTION_OPEN_DEPENDENCIES.equals(action)) {
            DependenciesDialogOpener opener = currentDependenciesDialogOpener();
            if (opener != null) {
                opener.openDependenciesDialog();
            } else {
                currentUiHooks().showMessage(analysis,
                        "Pipeline Dependencies is not available in this context.\n\n" + plainMessage);
            }
            return GateDecision.BLOCKED;
        }

        if (ACTION_CHANGE_SETUP.equals(action)) {
            return GateDecision.CHANGE_SETUP;
        }

        if (action != null && action.startsWith(ACTION_FIX_PREFIX)) {
            String actionId = action.substring(ACTION_FIX_PREFIX.length());
            if (actionId.trim().isEmpty()) {
                actionId = DependencyService.DialogAction.AUTO_FIX;
            }
            DependencyFixResult result = service.runDialogAction(id, actionId);
            currentUiHooks().showMessage(analysis, buildFixResultMessage(analysis, requirement, result));
            if (result != null && result.isSuccess() && !result.isRestartRequired()) {
                DependencyStatus after = service.getStatus(id);
                if (after != null && after.isPresent()) {
                    return GateDecision.ALLOWED;
                }
            }
            return GateDecision.BLOCKED;
        }

        return GateDecision.BLOCKED;
    }

    private static DependencyService currentDependencyService() {
        synchronized (LOCK) {
            return dependencyService;
        }
    }

    private static DependenciesDialogOpener currentDependenciesDialogOpener() {
        synchronized (LOCK) {
            return dependenciesDialogOpener;
        }
    }

    private static UiHooks currentUiHooks() {
        synchronized (LOCK) {
            return uiHooks;
        }
    }

    private static boolean isHeadless() {
        synchronized (LOCK) {
            return headlessMode || uiHooks.isHeadless();
        }
    }

    private static List<GateAction> buildGateActions(DependencySpec spec) {
        List<GateAction> actions = new ArrayList<GateAction>();
        if (spec != null && spec.isFixableInApp()) {
            if (!spec.getInstallOptions().isEmpty()) {
                for (DependencySpec.InstallOption option : spec.getInstallOptions()) {
                    String label = option.formatButtonLabel();
                    if (label != null && !label.trim().isEmpty()) {
                        actions.add(new GateAction(ACTION_FIX_PREFIX + option.getActionId(), label.trim()));
                    }
                }
            } else {
                String label = spec.formatButtonLabel(null);
                if (label == null || label.trim().isEmpty()) {
                    label = "Auto-Fix";
                }
                actions.add(new GateAction(ACTION_FIX_PREFIX + DependencyService.DialogAction.AUTO_FIX,
                        label.trim()));
            }
        }
        if (spec == null || spec.isVisibleInDependenciesDialog()) {
            actions.add(new GateAction(ACTION_OPEN_DEPENDENCIES, "Open Dependencies"));
        }
        actions.add(new GateAction(ACTION_CHANGE_SETUP, "Go Back / Change Setup"));
        return Collections.unmodifiableList(actions);
    }

    private static String normalizeAnalysisName(String analysisDisplayName) {
        if (analysisDisplayName == null || analysisDisplayName.trim().isEmpty()) {
            return "Requested analysis";
        }
        return analysisDisplayName.trim();
    }

    private static String normalizeRequirementName(String requirementDisplayName, String analysisDisplayName) {
        if (requirementDisplayName == null || requirementDisplayName.trim().isEmpty()) {
            return "";
        }
        String requirement = requirementDisplayName.trim();
        if (requirement.equals(analysisDisplayName)) {
            return "";
        }
        return requirement;
    }

    private static String buildPlainMessage(DependencySpec spec,
                                            DependencyStatus status,
                                            String analysis,
                                            String requirement) {
        StringBuilder sb = new StringBuilder();
        sb.append("Missing dependency: ").append(spec.getDisplayName()).append('\n');
        sb.append("Analysis you tried to run: ").append(analysis).append('\n');
        if (!requirement.isEmpty()) {
            sb.append("Required for: ").append(requirement).append('\n');
        }
        sb.append("Rest of the plugin still works.\n");
        sb.append(spec.isRestartRequired()
                ? "Restart Fiji after repair: required."
                : "Restart Fiji after repair: not required.");
        if (status != null && status.getDetailMessage() != null && !status.getDetailMessage().trim().isEmpty()) {
            sb.append("\n\n").append(status.getDetailMessage().trim());
        }
        return sb.toString();
    }

    private static String buildFixResultMessage(String analysis, String requirement, DependencyFixResult result) {
        StringBuilder sb = new StringBuilder();
        if (result != null && result.isSuccess()) {
            sb.append("Repair completed.");
            if (result.isRestartRequired()) {
                sb.append("\n\nRestart Fiji before retrying ").append(analysis).append('.');
            } else {
                sb.append("\n\n").append(analysis).append(" can continue.");
                if (!requirement.isEmpty()) {
                    sb.append("\nRequired feature: ").append(requirement).append('.');
                }
            }
        } else {
            sb.append(analysis).append(" remains blocked.");
            if (!requirement.isEmpty()) {
                sb.append("\nRequired feature: ").append(requirement).append('.');
            }
        }
        if (result != null && result.getMessage() != null && !result.getMessage().trim().isEmpty()) {
            sb.append("\n\n").append(result.getMessage().trim());
        }
        return sb.toString();
    }

    private static String htmlLine(String text) {
        return toHtml(text == null ? "" : text);
    }

    private static String toHtml(String text) {
        String safe = text == null ? "" : text;
        safe = safe.replace("&", "&amp;");
        safe = safe.replace("<", "&lt;");
        safe = safe.replace(">", "&gt;");
        return safe.replace("\n", "<br>");
    }

    private static final class SwingUiHooks implements UiHooks {
        @Override
        public boolean isHeadless() {
            return GraphicsEnvironment.isHeadless();
        }

        @Override
        public void log(String message) {
            IJ.log(message);
        }

        @Override
        public void showMessage(String title, String message) {
            IJ.showMessage(title, message);
        }

        @Override
        public String showMissingDependencyDialog(String analysis,
                                                  String requirement,
                                                  DependencySpec spec,
                                                  DependencyStatus status,
                                                  String plainMessage,
                                                  List<GateAction> actions) {
            PipelineDialog dialog = new PipelineDialog("Missing Dependency - " + analysis);
            dialog.setDefaultButtonsVisible(false);
            dialog.addHeader("Dependency Required");
            dialog.addMessage(htmlLine("Missing dependency: " + spec.getDisplayName()));
            dialog.addMessage(htmlLine("Analysis you tried to run: " + analysis));
            if (!requirement.isEmpty()) {
                dialog.addMessage(htmlLine("Required for: " + requirement));
            }
            dialog.addMessage("Rest of the plugin still works.");
            dialog.addHelpText("Auto-Fix uses the same repair action as the main Dependencies window. "
                    + "Go Back / Change Setup closes this warning so you can choose settings that do not need this dependency.");
            if (status != null && status.getDetailMessage() != null && !status.getDetailMessage().trim().isEmpty()) {
                dialog.addHelpText(toHtml(status.getDetailMessage().trim()));
            }
            dialog.addMessage(spec.isRestartRequired()
                    ? "Restart Fiji after repair: required."
                    : "Restart Fiji after repair: not required.");

            for (GateAction action : actions) {
                final GateAction gateAction = action;
                JButton button = dialog.addFooterButton(gateAction.getLabel());
                button.addActionListener(e -> dialog.closeWithAction(gateAction.getCommand()));
            }

            dialog.showDialog();
            return dialog.getActionCommand();
        }
    }
}
