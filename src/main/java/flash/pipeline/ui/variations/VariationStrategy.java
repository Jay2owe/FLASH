package flash.pipeline.ui.variations;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface VariationStrategy {
    void dispatch(ParameterSweep sweep,
                  Consumer<VariationResult> publisher,
                  BooleanSupplier cancelCheck) throws Exception;
}
