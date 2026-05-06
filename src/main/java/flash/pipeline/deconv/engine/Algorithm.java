package flash.pipeline.deconv.engine;

public enum Algorithm {
    RL(
            "Richardson-Lucy",
            "Classic iterative deconvolution for most fluorescence stacks."
    ),
    RL_TV(
            "Richardson-Lucy + TV",
            "Richardson-Lucy with total-variation regularization to suppress noise."
    ),
    TIKHONOV(
            "Tikhonov-Miller",
            "Quadratic regularization that trades some sharpness for stability."
    ),
    WIENER(
            "Wiener",
            "Frequency-domain deconvolution tuned by a regularization strength."
    ),
    LANDWEBER(
            "Landweber",
            "Conservative iterative deconvolution with a fixed step size."
    );

    private final String displayName;
    private final String description;

    Algorithm(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }
}
