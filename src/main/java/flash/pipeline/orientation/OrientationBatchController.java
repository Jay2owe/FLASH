package flash.pipeline.orientation;

import flash.pipeline.naming.OrientationManifestRow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Session-scoped state for reusing ROI orientation choices across a batch.
 */
public final class OrientationBatchController {
    private List<OrientationPreset> presets;
    private OrientationTransformState lastApplied;
    private BroadcastRule activeRule;
    private CurrentImage current;
    private int currentIndex;
    private final int totalImages;
    private final OrientationPresetStore store;

    public interface CurrentImage {
        OrientationManifestRow.Hemisphere hemisphere();
        OrientationTransformState state();
        void applyState(OrientationTransformState next);
    }

    public OrientationBatchController(OrientationPresetStore store, int totalImages) {
        if (store == null) {
            throw new IllegalArgumentException("Preset store must not be null.");
        }
        this.store = store;
        this.totalImages = Math.max(0, totalImages);
        this.presets = cleanPresets(store.load());
        this.currentIndex = -1;
    }

    public void bindCurrent(CurrentImage image, int index) {
        this.current = image;
        this.currentIndex = index;
    }

    public boolean applyActiveRuleOnOpen() {
        if (current == null || activeRule == null || !activeRule.isActive()) {
            return false;
        }

        OrientationTransformState next = activeRule.transformFor(currentHemisphere());
        if (next == null || sameState(currentState(), next)) {
            return false;
        }

        current.applyState(next);
        lastApplied = next;
        return true;
    }

    public boolean hasLastApplied() {
        return lastApplied != null;
    }

    public void repeatLast() {
        if (current == null || lastApplied == null) {
            return;
        }
        current.applyState(lastApplied);
    }

    public void noteManualState(OrientationTransformState state) {
        lastApplied = safeState(state);
    }

    public OrientationPreset savePreset(String name) throws IOException {
        OrientationPreset preset = new OrientationPreset(name, currentState());
        presets = cleanPresets(store.add(preset));
        return preset;
    }

    public List<OrientationPreset> presets() {
        return Collections.unmodifiableList(new ArrayList<OrientationPreset>(presets));
    }

    public void deletePreset(String name) throws IOException {
        presets = cleanPresets(store.removeByName(name));
    }

    public void applyPreset(OrientationPreset preset) {
        if (current == null || preset == null) {
            return;
        }
        OrientationTransformState next = safeState(preset.transform);
        current.applyState(next);
        lastApplied = next;
    }

    public void setRule(BroadcastScope scope) {
        if (scope == null || scope == BroadcastScope.THIS_IMAGE) {
            clearRule();
            return;
        }

        OrientationTransformState captured = currentState();
        activeRule = new BroadcastRule(scope, captured, currentHemisphere());

        OrientationTransformState next = activeRule.transformFor(currentHemisphere());
        if (current != null && next != null && !sameState(currentState(), next)) {
            current.applyState(next);
            lastApplied = next;
        }
    }

    public void clearRule() {
        activeRule = null;
    }

    public BroadcastRule activeRule() {
        return activeRule;
    }

    public String ruleStatusText() {
        if (activeRule == null || !activeRule.isActive()) {
            return "";
        }

        // SAME_HEMISPHERE is an upper bound: the controller does not know
        // future image hemispheres without changing the per-image loop shape.
        return "Rule active: " + ruleScopeLabel(activeRule)
                + " (" + remainingImages() + " remaining)";
    }

    public OrientationManifestRow.Hemisphere currentHemisphere() {
        if (current == null || current.hemisphere() == null) {
            return OrientationManifestRow.Hemisphere.UNKNOWN;
        }
        return current.hemisphere();
    }

    private OrientationTransformState currentState() {
        return current == null ? OrientationTransformState.identity() : safeState(current.state());
    }

    private int remainingImages() {
        return Math.max(0, totalImages - currentIndex - 1);
    }

    private static String ruleScopeLabel(BroadcastRule rule) {
        switch (rule.scope) {
            case SAME_HEMISPHERE:
                if (rule.sourceHemisphere == OrientationManifestRow.Hemisphere.LH) {
                    return "apply to all LH images";
                }
                if (rule.sourceHemisphere == OrientationManifestRow.Hemisphere.RH) {
                    return "apply to all RH images";
                }
                return "apply to same-hemisphere images";
            case ALL_LITERAL:
                return "apply to all images";
            case ALL_MIRRORED:
                return "apply to all images, mirror L<->R";
            case THIS_IMAGE:
            default:
                return "apply to this image";
        }
    }

    private static OrientationTransformState safeState(OrientationTransformState state) {
        return state == null ? OrientationTransformState.identity() : state;
    }

    private static boolean sameState(OrientationTransformState a,
                                     OrientationTransformState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.rotateDegrees == b.rotateDegrees
                && a.flipHorizontal == b.flipHorizontal
                && a.flipVertical == b.flipVertical;
    }

    private static List<OrientationPreset> cleanPresets(List<OrientationPreset> input) {
        List<OrientationPreset> out = new ArrayList<OrientationPreset>();
        if (input == null) {
            return out;
        }
        for (int i = 0; i < input.size(); i++) {
            OrientationPreset preset = input.get(i);
            if (preset != null) {
                out.add(preset);
            }
        }
        return out;
    }
}
