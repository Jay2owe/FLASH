package flash.pipeline.deconv.psf;

public final class PsfSpec {

    private final double numericalAperture;
    private final double immersionRI;
    private final double sampleRI;
    private final double emissionWavelengthNm;
    private final double pixelSizeXyNm;
    private final double pixelSizeZNm;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final ScopeModality scopeModality;
    private final Double pinholeAiryUnits;

    public PsfSpec(double numericalAperture,
                   double immersionRI,
                   double sampleRI,
                   double emissionWavelengthNm,
                   double pixelSizeXyNm,
                   double pixelSizeZNm,
                   int sizeX,
                   int sizeY,
                   int sizeZ,
                   ScopeModality scopeModality,
                   Double pinholeAiryUnits) {
        this.numericalAperture = requirePositiveFinite("numericalAperture", numericalAperture);
        this.immersionRI = requirePositiveFinite("immersionRI", immersionRI);
        this.sampleRI = requirePositiveFinite("sampleRI", sampleRI);
        this.emissionWavelengthNm = requirePositiveFinite("emissionWavelengthNm", emissionWavelengthNm);
        this.pixelSizeXyNm = requirePositiveFinite("pixelSizeXyNm", pixelSizeXyNm);
        this.pixelSizeZNm = requirePositiveFinite("pixelSizeZNm", pixelSizeZNm);
        this.sizeX = requirePositive("sizeX", sizeX);
        this.sizeY = requirePositive("sizeY", sizeY);
        this.sizeZ = requirePositive("sizeZ", sizeZ);
        if (scopeModality == null) {
            throw new IllegalArgumentException("scopeModality is required.");
        }
        this.scopeModality = scopeModality;
        this.pinholeAiryUnits = requireNullablePositiveFinite("pinholeAiryUnits", pinholeAiryUnits);
    }

    public double getNumericalAperture() {
        return numericalAperture;
    }

    public double getImmersionRI() {
        return immersionRI;
    }

    public double getSampleRI() {
        return sampleRI;
    }

    public double getEmissionWavelengthNm() {
        return emissionWavelengthNm;
    }

    public double getPixelSizeXyNm() {
        return pixelSizeXyNm;
    }

    public double getPixelSizeZNm() {
        return pixelSizeZNm;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSizeZ() {
        return sizeZ;
    }

    public ScopeModality getScopeModality() {
        return scopeModality;
    }

    public Double getPinholeAiryUnits() {
        return pinholeAiryUnits;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PsfSpec)) return false;

        PsfSpec that = (PsfSpec) other;
        return bits(numericalAperture) == bits(that.numericalAperture)
                && bits(immersionRI) == bits(that.immersionRI)
                && bits(sampleRI) == bits(that.sampleRI)
                && bits(emissionWavelengthNm) == bits(that.emissionWavelengthNm)
                && bits(pixelSizeXyNm) == bits(that.pixelSizeXyNm)
                && bits(pixelSizeZNm) == bits(that.pixelSizeZNm)
                && sizeX == that.sizeX
                && sizeY == that.sizeY
                && sizeZ == that.sizeZ
                && scopeModality == that.scopeModality
                && nullableBits(pinholeAiryUnits) == nullableBits(that.pinholeAiryUnits);
    }

    @Override
    public int hashCode() {
        int result = hash(bits(numericalAperture));
        result = 31 * result + hash(bits(immersionRI));
        result = 31 * result + hash(bits(sampleRI));
        result = 31 * result + hash(bits(emissionWavelengthNm));
        result = 31 * result + hash(bits(pixelSizeXyNm));
        result = 31 * result + hash(bits(pixelSizeZNm));
        result = 31 * result + sizeX;
        result = 31 * result + sizeY;
        result = 31 * result + sizeZ;
        result = 31 * result + scopeModality.hashCode();
        result = 31 * result + hash(nullableBits(pinholeAiryUnits));
        return result;
    }

    @Override
    public String toString() {
        return "PsfSpec{"
                + "numericalAperture=" + numericalAperture
                + ", immersionRI=" + immersionRI
                + ", sampleRI=" + sampleRI
                + ", emissionWavelengthNm=" + emissionWavelengthNm
                + ", pixelSizeXyNm=" + pixelSizeXyNm
                + ", pixelSizeZNm=" + pixelSizeZNm
                + ", sizeX=" + sizeX
                + ", sizeY=" + sizeY
                + ", sizeZ=" + sizeZ
                + ", scopeModality=" + scopeModality
                + ", pinholeAiryUnits=" + pinholeAiryUnits
                + '}';
    }

    private static double requirePositiveFinite(String label, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(label + " must be finite and > 0 (was " + value + ").");
        }
        return value;
    }

    private static int requirePositive(String label, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be > 0 (was " + value + ").");
        }
        return value;
    }

    private static Double requireNullablePositiveFinite(String label, Double value) {
        if (value == null) return null;
        if (Double.isNaN(value.doubleValue())
                || Double.isInfinite(value.doubleValue())
                || value.doubleValue() <= 0.0) {
            throw new IllegalArgumentException(label + " must be null or finite and > 0 (was " + value + ").");
        }
        return value;
    }

    private static long bits(double value) {
        return Double.doubleToLongBits(value);
    }

    private static long nullableBits(Double value) {
        return value == null ? 0L : Double.doubleToLongBits(value.doubleValue());
    }

    private static int hash(long value) {
        return (int) (value ^ (value >>> 32));
    }
}
