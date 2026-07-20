package flash.pipeline.deconv.psf;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;

/**
 * Pure-Java scalar 3D point-spread function synthesizer.
 *
 * <p>Replaces the broken {@code IJ.run("PSF Generator", ...)} path: the EPFL PSF_Generator
 * plugin's {@code run(String)} method only displays a Swing dialog and ignores macro
 * options, so it cannot be driven headlessly by another plugin.
 *
 * <p>The math:
 * <ul>
 *   <li><b>Born &amp; Wolf</b> — scalar Kirchhoff diffraction integral
 *       {@code I(v,u) = |2·∫₀¹ J₀(v·ρ)·exp(i·u·ρ²/2)·ρ dρ|²}.</li>
 *   <li><b>Gibson &amp; Lanni</b> — adds depth-induced spherical aberration when the sample
 *       refractive index differs from the immersion medium.</li>
 *   <li><b>Dougherty</b> — fast 3D Gaussian approximation with FWHM derived from NA, λ, n.</li>
 * </ul>
 *
 * <p>Confocal modality squares the widefield intensity (excitation × emission), which is the
 * standard approximation for ≈1 Airy-unit pinholes.
 *
 * <p>Output is a 32-bit float {@link ImagePlus} with sum normalized to 1.0. Born-Wolf and
 * Gaussian PSFs peak at the geometric center; Gibson-Lanni mismatch can shift the brightest
 * axial voxel before the adapter recenters it for deconvolution engines.
 */
public final class ScalarPsfSynthesizer {

    private static final int MIN_SIMPSON_STEPS = 128;
    private static final int MAX_SIMPSON_STEPS = 2048;
    private static final int RADIAL_SUBPIXELS = 4;
    private static final double GIBSON_LANNI_DEFAULT_SAMPLE_DEPTH_NM = 2000.0;

    private ScalarPsfSynthesizer() {}

    public static ImagePlus synthesize(PsfSpec spec, PsfModel model) {
        if (spec == null) throw new IllegalArgumentException("spec is required.");
        if (model == null) throw new IllegalArgumentException("model is required.");

        float[][] planes = computeVolume(spec, model);
        ImagePlus image = toImagePlus(planes, spec, model);
        normalizeInPlace(image);
        return image;
    }

    private static float[][] computeVolume(PsfSpec spec, PsfModel model) {
        int nx = spec.getSizeX();
        int ny = spec.getSizeY();
        int nz = spec.getSizeZ();
        int cx = nx / 2;
        int cy = ny / 2;
        int cz = nz / 2;
        double pixelXyNm = spec.getPixelSizeXyNm();
        double pixelZNm = spec.getPixelSizeZNm();
        double lambdaNm = spec.getEmissionWavelengthNm();
        double na = spec.getNumericalAperture();
        double ni = spec.getImmersionRI();
        double ns = spec.getSampleRI();
        double k = 2.0 * Math.PI / lambdaNm;
        boolean gibsonLanni = model == PsfModel.GIBSON_LANNI;
        boolean confocal = isConfocalLike(spec.getScopeModality());

        double maxDx = Math.max(cx, nx - 1 - cx);
        double maxDy = Math.max(cy, ny - 1 - cy);
        double maxRadiusPx = Math.sqrt(maxDx * maxDx + maxDy * maxDy) + 1.0;
        int radialSamples = (int) Math.ceil(maxRadiusPx * RADIAL_SUBPIXELS) + 1;

        float[][] planes = new float[nz][nx * ny];

        for (int iz = 0; iz < nz; iz++) {
            double zNm = (iz - cz) * pixelZNm;
            double u = k * na * na * zNm / ni;

            double[] radial = new double[radialSamples];
            for (int r = 0; r < radialSamples; r++) {
                double rNm = (r / (double) RADIAL_SUBPIXELS) * pixelXyNm;
                double v = k * na * rNm;
                double intensity;
                if (model == PsfModel.DOUGHERTY_THEORETICAL) {
                    intensity = gaussianIntensity(rNm, zNm, lambdaNm, na, ni);
                } else {
                    intensity = scalarIntegralIntensity(v, u, zNm, na, ni, ns, k, gibsonLanni);
                }
                if (confocal) {
                    intensity = intensity * intensity;
                }
                radial[r] = intensity;
            }

            float[] plane = planes[iz];
            for (int iy = 0; iy < ny; iy++) {
                double dy = iy - cy;
                int row = iy * nx;
                for (int ix = 0; ix < nx; ix++) {
                    double dx = ix - cx;
                    double rPx = Math.sqrt(dx * dx + dy * dy);
                    double idxF = rPx * RADIAL_SUBPIXELS;
                    int idx = (int) idxF;
                    double frac = idxF - idx;
                    double value;
                    if (idx + 1 < radialSamples) {
                        value = radial[idx] + frac * (radial[idx + 1] - radial[idx]);
                    } else if (idx < radialSamples) {
                        value = radial[idx];
                    } else {
                        value = radial[radialSamples - 1];
                    }
                    plane[row + ix] = (float) value;
                }
            }
        }
        return planes;
    }

    /**
     * Scalar diffraction integral intensity. When {@code gibsonLanni} is true and the sample
     * RI differs from the immersion RI, the Gibson-Lanni depth-induced aberration phase is
     * added; otherwise this reduces to the classic Born-Wolf formulation.
     */
    static double scalarIntegralIntensity(double v, double u, double zNm,
                                          double na, double ni, double ns, double k,
                                          boolean gibsonLanni) {
        double rhoMax = gibsonLanni ? gibsonLanniPupilRadius(na, ns) : 1.0;
        int steps = integrationSteps(v, u, rhoMax);
        return scalarIntegralIntensity(v, u, zNm, na, ni, ns, k, gibsonLanni, steps);
    }

    static double scalarIntegralIntensity(double v, double u, double zNm,
                                          double na, double ni, double ns, double k,
                                          boolean gibsonLanni,
                                          int requestedSteps) {
        double rhoMax = gibsonLanni ? gibsonLanniPupilRadius(na, ns) : 1.0;
        int steps = evenClampedSteps(requestedSteps);
        double h = rhoMax / steps;
        double sumRe = 0.0;
        double sumIm = 0.0;
        for (int i = 0; i <= steps; i++) {
            double rho = i * h;
            double weight = simpsonWeight(i, steps);
            double phase = u * rho * rho * 0.5;
            if (gibsonLanni) {
                phase += gibsonLanniMismatchPhase(rho, na, ni, ns, k);
            }
            double j0r = j0(v * rho) * rho;
            sumRe += weight * j0r * Math.cos(phase);
            sumIm += weight * j0r * Math.sin(phase);
        }
        sumRe *= h / 3.0;
        sumIm *= h / 3.0;
        return 4.0 * (sumRe * sumRe + sumIm * sumIm);
    }

    private static double gibsonLanniMismatchPhase(double rho,
                                                   double na, double ni, double ns, double k) {
        if (Math.abs(ns - ni) <= 1e-12) {
            return 0.0;
        }
        double sinThetaI = na * rho / ni;
        double sinThetaS = na * rho / ns;
        double cosThetaI = cosFromSine(sinThetaI);
        double cosThetaS = cosFromSine(sinThetaS);
        double opticalPathDifference = ns * cosThetaS - ni * cosThetaI - (ns - ni);
        return k * GIBSON_LANNI_DEFAULT_SAMPLE_DEPTH_NM * opticalPathDifference;
    }

    private static double gaussianIntensity(double rNm, double zNm,
                                            double lambdaNm, double na, double ni) {
        double sigmaXy = 0.21 * lambdaNm / na;
        double naOverN = Math.min(na / ni, 0.999);
        double axialFwhm = 0.88 * lambdaNm / (ni - Math.sqrt(Math.max(0.0, ni * ni - na * na)));
        double sigmaZ = Math.max(axialFwhm / 2.3548, 1e-6);
        double exponent = -(rNm * rNm) / (2.0 * sigmaXy * sigmaXy)
                - (zNm * zNm) / (2.0 * sigmaZ * sigmaZ);
        // naOverN kept in scope to ensure the lateral term is exercised even for very low NA;
        // the lateral sigma is the dominant correctness signal.
        if (naOverN < 0.0) return 0.0;
        return Math.exp(exponent);
    }

    private static boolean isConfocalLike(ScopeModality modality) {
        return modality == ScopeModality.CONFOCAL || modality == ScopeModality.SPINNING_DISK;
    }

    static int integrationSteps(double v, double u, double rhoMax) {
        if (Double.isNaN(v) || Double.isInfinite(v)
                || Double.isNaN(u) || Double.isInfinite(u)
                || Double.isNaN(rhoMax) || Double.isInfinite(rhoMax) || rhoMax <= 0.0) {
            return MIN_SIMPSON_STEPS;
        }
        double besselPhase = Math.abs(v) * rhoMax;
        double defocusPhase = Math.abs(u) * rhoMax * rhoMax * 0.5;
        double phase = Math.max(besselPhase, defocusPhase);
        int requested = (int) Math.ceil(phase * 8.0);
        return evenClampedSteps(Math.max(MIN_SIMPSON_STEPS, requested));
    }

    private static int evenClampedSteps(int requestedSteps) {
        int steps = Math.max(MIN_SIMPSON_STEPS, Math.min(MAX_SIMPSON_STEPS, requestedSteps));
        return (steps % 2 == 0) ? steps : steps + 1;
    }

    private static double gibsonLanniPupilRadius(double na, double ns) {
        return Math.min(1.0, ns / na);
    }

    private static double cosFromSine(double sinTheta) {
        if (sinTheta >= 1.0) return 0.0;
        if (sinTheta <= -1.0) return 0.0;
        return Math.sqrt(Math.max(0.0, 1.0 - sinTheta * sinTheta));
    }

    private static double simpsonWeight(int i, int n) {
        if (i == 0 || i == n) return 1.0;
        return (i % 2 == 1) ? 4.0 : 2.0;
    }

    /**
     * Bessel function J₀ via Abramowitz &amp; Stegun rational approximations
     * (9.4.1 for |x| &lt; 3 and 9.4.3 for |x| ≥ 3). Accuracy ≈ 1e-7.
     */
    static double j0(double x) {
        double a = x < 0.0 ? -x : x;
        if (a < 3.0) {
            double t = (x / 3.0) * (x / 3.0);
            return 1.0 + t * (-2.2499997 + t * (1.2656208 + t * (-0.3163866
                    + t * (0.0444479 + t * (-0.0039444 + t * 0.00021)))));
        }
        double y = 3.0 / a;
        double f = 0.79788456 + y * (-7.7e-7 + y * (-0.0055274 + y * (-9.512e-5
                + y * (0.00137237 + y * (-7.2805e-4 + y * 1.4476e-4)))));
        double theta = a - 0.78539816 + y * (-0.04166397 + y * (-3.954e-5 + y * (0.00262573
                + y * (-5.4125e-4 + y * (-2.9333e-4 + y * 1.3558e-4)))));
        return f * Math.cos(theta) / Math.sqrt(a);
    }

    private static ImagePlus toImagePlus(float[][] planes, PsfSpec spec, PsfModel model) {
        int nz = planes.length;
        int nx = spec.getSizeX();
        int ny = spec.getSizeY();
        ImageStack stack = new ImageStack(nx, ny);
        for (int z = 0; z < nz; z++) {
            stack.addSlice(null, new FloatProcessor(nx, ny, planes[z], null));
        }
        ImagePlus image = new ImagePlus("PSF_" + model.name(), stack);
        Calibration cal = new Calibration();
        cal.pixelWidth = spec.getPixelSizeXyNm() / 1000.0;
        cal.pixelHeight = spec.getPixelSizeXyNm() / 1000.0;
        cal.pixelDepth = spec.getPixelSizeZNm() / 1000.0;
        cal.setUnit("micron");
        image.setCalibration(cal);
        return image;
    }

    private static void normalizeInPlace(ImagePlus image) {
        ImageStack stack = image.getStack();
        double sum = 0.0;
        for (int z = 1; z <= stack.getSize(); z++) {
            FloatProcessor fp = (FloatProcessor) stack.getProcessor(z);
            int n = fp.getPixelCount();
            for (int i = 0; i < n; i++) {
                float v = fp.getf(i);
                if (Float.isNaN(v) || Float.isInfinite(v)) {
                    fp.setf(i, 0.0f);
                    continue;
                }
                sum += v;
            }
        }
        if (sum <= 0.0 || Double.isNaN(sum) || Double.isInfinite(sum)) {
            throw new IllegalStateException("Synthesized PSF has non-positive or non-finite sum (was " + sum + ").");
        }
        float scale = (float) (1.0 / sum);
        for (int z = 1; z <= stack.getSize(); z++) {
            FloatProcessor fp = (FloatProcessor) stack.getProcessor(z);
            int n = fp.getPixelCount();
            for (int i = 0; i < n; i++) {
                fp.setf(i, fp.getf(i) * scale);
            }
        }
    }
}
