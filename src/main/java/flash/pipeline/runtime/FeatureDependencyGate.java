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

    interface UiHooks {
        boolean isHeadless();

        void log(String message);

        void showMessage(String title, String message);

        String showMissingDependencyDialog(String feature,
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
        DependencySpec spec = DependencyRegistry.get(id);
        String feature = normalizeFeatureName(featureDisplayName);
        if (spec == null) {
            currentUiHooks().log("Dependency gate failed: unknown dependency id " + id + " for " + feature + ".");
            return false;
        }

        DependencyService service = currentDependencyService();
        DependencyStatus status = service.getStatus(id);
        if (status != null && status.isPresent()) {
            return true;
        }

        String plainMessage = buildPlainMessage(spec, status, feature);
        if (isHeadless()) {
            currentUiHooks().log("Blocked feature: " + feature);
            currentUiHooks().log(plainMessage);
            return false;
        }

        String action = currentUiHooks().showMissingDependencyDialog(
                feature,
                spec,
                status,
                plainMessage,
                buildGateActions(spec));
        if ("open_dependencies".equals(action)) {
            DependenciesDialogOpener opener = currentDependenciesDialogOpener();
            if (opener != null) {
                opener.openDependenciesDialog();
            } else {
                currentUiHooks().showMessage(feature,
                        "Pipeline Dependencies is not available in this context.\n\n" + plainMessage);
            }
            return false;
        }

        if ("fix_now".equals(action)) {
            DependencyFixResult result = service.runDialogAction(id, DependencyService.DialogAction.AUTO_FIX);
            currentUiHooks().showMessage(feature, buildFixResultMessage(feature, result));
            return false;
        }

        return false;
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
        actions.add(new GateAction("open_dependencies", "Open Dependencies"));
        if (spec.isFixableInApp()) {
            actions.add(new GateAction("fix_now", "Fix Now"));
        }
        actions.add(new GateAction("close", "Close"));
        return Collections.unmodifiableList(actions);
    }

    private static String normalizeFeatureName(String featureDisplayName) {
        if (featureDisplayName == null || featureDisplayName.trim().isEmpty()) {
            return "Requested feature";
        }
        return featureDisplayName.trim();
    }

    private static String buildPlainMessage(DependencySpec spec, DependencyStatus status, String feature) {
        StringBuilder sb = new StringBuilder();
        sb.append("Missing dependency: ").append(spec.getDisplayName()).append('\n');
        sb.append("Blocked feature: ").append(feature).append('\n');
        sb.append("Rest of the plugin still works.\n");
        sb.append(spec.isRestartRequired()
                ? "Restart Fiji after repair: required."
                : "Restart Fiji after repair: not required.");
        if (status != null && status.getDetailMessage() != null && !status.getDetailMessage().trim().isEmpty()) {
            sb.append("\n\n").append(status.getDetailMessage().trim());
        }
        return sb.toString();
    }

    private static String buildFixResultMessage(String feature, DependencyFixResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(feature).append(" remains blocked for this run.");
        if (result != null && result.getMessage() != null && !result.getMessage().trim().isEmpty()) {
            sb.append("\n\n").append(result.getMessage().trim());
        }
        if (result != null && result.isRestartRequired()) {
            sb.append("\n\nRestart Fiji before retrying ").append(feature).append('.');
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
        public String showMissingDependencyDialog(String feature,
                                                  DependencySpec spec,
                                                  DependencyStatus status,
                                                  String plainMessage,
                                                  List<GateAction> actions) {
            PipelineDialog dialog = new PipelineDialog(feature);
            dialog.setDefaultButtonsVisible(false);
            dialog.addHeader("Dependency Required");
            dialog.addMessage(htmlLine("Missing dependency: " + spec.getDisplayName()));
            dialog.addMessage(htmlLine("Blocked feature: " + feature));
            dialog.addMessage("Rest of the plugin still works.");
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
