package flash.pipeline.deconv.engine;

public final class DeconvParams {

    public static final int DEFAULT_ITERATIONS = 15;
    public static final double DEFAULT_REGULARIZATION = 0.01;
    public static final EdgeHandling DEFAULT_EDGE_HANDLING = EdgeHandling.REFLECT;

    private final Algorithm algorithm;
    private final int iterations;
    private final double regularization;
    private final EdgeHandling edgeHandling;

    private DeconvParams(Builder builder) {
        if (builder.algorithm == null) {
            throw new IllegalArgumentException("algorithm is required.");
        }
        this.algorithm = builder.algorithm;
        this.iterations = requireIterationCount(builder.iterations);
        this.regularization = requireRegularization(builder.regularization);
        if (builder.edgeHandling == null) {
            throw new IllegalArgumentException("edgeHandling is required.");
        }
        this.edgeHandling = builder.edgeHandling;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(Algorithm algorithm) {
        return new Builder().algorithm(algorithm);
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }

    public int getIterations() {
        return iterations;
    }

    public double getRegularization() {
        return regularization;
    }

    public EdgeHandling getEdgeHandling() {
        return edgeHandling;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DeconvParams)) return false;

        DeconvParams that = (DeconvParams) other;
        return iterations == that.iterations
                && Double.doubleToLongBits(regularization) == Double.doubleToLongBits(that.regularization)
                && algorithm == that.algorithm
                && edgeHandling == that.edgeHandling;
    }

    @Override
    public int hashCode() {
        int result = algorithm.hashCode();
        long regularizationBits = Double.doubleToLongBits(regularization);
        result = 31 * result + iterations;
        result = 31 * result + (int) (regularizationBits ^ (regularizationBits >>> 32));
        result = 31 * result + edgeHandling.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "DeconvParams{"
                + "algorithm=" + algorithm
                + ", iterations=" + iterations
                + ", regularization=" + regularization
                + ", edgeHandling=" + edgeHandling
                + '}';
    }

    private static int requireIterationCount(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("iterations must be in the range 1-100.");
        }
        return value;
    }

    private static double requireRegularization(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0 || value > 0.1) {
            throw new IllegalArgumentException("regularization must be finite and in the range 0.0-0.1.");
        }
        return value;
    }

    public static final class Builder {
        private Algorithm algorithm;
        private int iterations = DEFAULT_ITERATIONS;
        private double regularization = DEFAULT_REGULARIZATION;
        private EdgeHandling edgeHandling = DEFAULT_EDGE_HANDLING;

        private Builder() {}

        public Builder algorithm(Algorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder iterations(int iterations) {
            this.iterations = iterations;
            return this;
        }

        public Builder regularization(double regularization) {
            this.regularization = regularization;
            return this;
        }

        public Builder edgeHandling(EdgeHandling edgeHandling) {
            this.edgeHandling = edgeHandling;
            return this;
        }

        public DeconvParams build() {
            return new DeconvParams(this);
        }
    }
}
