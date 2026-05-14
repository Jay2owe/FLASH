package flash.pipeline.ui.variations;

public interface ParameterKey {

    enum ValueKind {
        NUMBER,
        STRING
    }

    String stableKey();

    String displayLabel();

    ValueKind valueKind();
}
