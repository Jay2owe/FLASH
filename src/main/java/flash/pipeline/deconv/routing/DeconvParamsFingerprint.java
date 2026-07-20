package flash.pipeline.deconv.routing;

import flash.pipeline.deconv.DeconvolutionIO;

import java.util.Locale;
import java.util.TreeMap;

/**
 * A compact, stable fingerprint of the per-channel deconvolution parameters — shared optics
 * (NA, immersion/sample RI, xy pixel size, z-step, emission wavelength, modality, pinhole) plus
 * the engine/algorithm/iterations/regularization/PSF model. Used as a cache discriminator by the
 * Set&nbsp;Up&nbsp;Configuration preview seam (Stage 13) and the composition layer (Stage 14) so a
 * change to any optics or engine parameter invalidates cached deconvolved previews/stacks.
 *
 * <p>The hashing is delegated to {@link DeconvolutionIO#paramsHash} so the fingerprint stays
 * consistent with the batch's {@code buildHashParams} semantics (same canonicalisation, same digest).
 * Per-image geometry that is not a deconvolution parameter (image width/height/z-count) is
 * intentionally excluded — it is folded into the batch's own paramsHash, not into this preview/cache
 * fingerprint.</p>
 */
public final class DeconvParamsFingerprint {

    private DeconvParamsFingerprint() {}

    public static String of(double na,
                            double immersionRi,
                            double sampleRi,
                            double xyPixelSizeUm,
                            double zStepUm,
                            double emissionWavelengthNm,
                            String engineKey,
                            String algorithm,
                            int iterations,
                            double regularization,
                            String psfModel,
                            String scopeModality,
                            Double pinholeAiryUnits) {
        TreeMap<String, String> params = new TreeMap<String, String>();
        params.put("na", fmt(na));
        params.put("immersionRi", fmt(immersionRi));
        params.put("sampleRi", fmt(sampleRi));
        params.put("xyPixelSizeUm", fmt(xyPixelSizeUm));
        params.put("zStepUm", fmt(zStepUm));
        params.put("wavelengthNm", fmt(emissionWavelengthNm));
        params.put("engine", nullToEmpty(engineKey));
        params.put("algorithm", nullToEmpty(algorithm));
        params.put("iterations", String.valueOf(iterations));
        params.put("regularization", fmt(regularization));
        params.put("psfModel", nullToEmpty(psfModel));
        params.put("scopeModality", nullToEmpty(scopeModality));
        params.put("pinhole", pinholeAiryUnits == null ? "" : fmt(pinholeAiryUnits.doubleValue()));
        return DeconvolutionIO.paramsHash(params);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
