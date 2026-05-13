package flash.pipeline.ui.variations;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class EchoStrategy implements VariationStrategy {

    @Override
    public void dispatch(ParameterSweep sweep,
                         Consumer<VariationResult> publisher,
                         BooleanSupplier cancelCheck) {
        List<ParameterCombo> combos = sweep.combos();
        for (int i = 0; i < combos.size(); i++) {
            if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                return;
            }
            ParameterCombo combo = combos.get(i);
            long started = System.currentTimeMillis();
            int syntheticCount = 10 + Math.abs(combo.toCanonicalJson().hashCode() % 90);
            long duration = Math.max(1L, System.currentTimeMillis() - started);
            publisher.accept(VariationResult.success(combo, null,
                    syntheticCount, duration, null));
        }
    }
}
