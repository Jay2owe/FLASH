package flash.pipeline.deconv;

import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvolutionEngine;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.engine.EngineRegistry;
import flash.pipeline.deconv.psf.ScopeModality;

import java.util.HashMap;
import java.util.Map;

/**
 * The single, authoritative definition of the deconvolution-mirror params-hash field set.
 *
 * <p>The mirror-writing params hash is content that must agree byte-for-byte across every producer:
 * the standalone batch writer ({@code DeconvolutionAnalysis.buildHashParams}), the Set&nbsp;Up
 * Configuration QC writer ({@code CreateBinFileAnalysis.buildSetupDeconvHashParams}), and the
 * consumer/preflight freshness check that recomputes the <em>expected</em> hash to detect a
 * parameter change. Historically the field set was hand-rolled in each place; if any copy drifted, a
 * consumer's expected hash would diverge from the writer's, <b>every</b> mirror would read as stale,
 * and consumers would silently fall back to raw — a worse regression than the params-staleness gap
 * this class closes. Centralising the field set here (and having every producer delegate to it) is
 * that safety mechanism.</p>
 *
 * <p>The source-independent base has 20 keys split into two groups. A source-aware artifact writer
 * adds four immutable identity keys through {@link #withArtifactIdentity}:</p>
 * <ul>
 *   <li><b>Config-derived</b> (11): {@code engine, algorithm, iterations, regularization, psfModel,
 *       scopeModality, pinhole, na, immersionRi, sampleRi, wavelengthNm} — sourced from the per-channel
 *       deconv settings + shared optics, i.e. the persisted {@code ChannelConfig}.</li>
 *   <li><b>Geometry/constant</b> (9): {@code pixelSizeXUm, pixelSizeZUm, sizeX, sizeY, sizeZ,
 *       psfSizeX, psfSizeY, psfSizeZ, trailingBlankSlicePolicy} — a pure function of the raw source
 *       image (dimensions + calibration), never of the config.</li>
 * </ul>
 *
 * <p>Because the geometry keys depend only on the source, and the source is verified independently by
 * the manifest's content fingerprint, the freshness check compares only the config-derived subset
 * (see {@link #buildConfigParams}); {@link DeconvManifest} overlays those onto the mirror's recorded
 * geometry, so a param change flips the hash while a Dropbox re-hydration (unchanged content) does
 * not.</p>
 *
 * <p>Deliberately ImageJ-free so it is unit-testable without booting Fiji. The hashing itself lives in
 * {@link DeconvolutionIO#paramsHash} (SHA-1 + Base32 canonicalisation); this class owns only the field
 * set. The PSF-kernel-size constants mirror the per-analysis constants used for the real kernel so the
 * hashed {@code psfSize*} equals the size the engine actually synthesises; the round-trip test guards
 * consistency of the whole hash.</p>
 */
public final class DeconvParamsHash {

    /** Kernel size caps — must match {@code DeconvolutionAnalysis.MAX_PSF_SIZE_*} and the setup copy. */
    static final int MAX_PSF_SIZE_XY = 257;
    static final int MAX_PSF_SIZE_Z = 127;

    /** Trailing-blank-slice trimming policy identifier — must match the per-analysis copies. */
    static final String TRAILING_BLANK_SLICE_POLICY = "trim-trailing-blank-v1";

    // Config-derived keys.
    private static final String K_ENGINE = "engine";
    private static final String K_ALGORITHM = "algorithm";
    private static final String K_ITERATIONS = "iterations";
    private static final String K_REGULARIZATION = "regularization";
    private static final String K_PSF_MODEL = "psfModel";
    private static final String K_SCOPE_MODALITY = "scopeModality";
    private static final String K_PINHOLE = "pinhole";
    private static final String K_NA = "na";
    private static final String K_IMMERSION_RI = "immersionRi";
    private static final String K_SAMPLE_RI = "sampleRi";
    private static final String K_WAVELENGTH_NM = "wavelengthNm";

    // Geometry / constant keys.
    private static final String K_PIXEL_SIZE_X_UM = "pixelSizeXUm";
    private static final String K_PIXEL_SIZE_Z_UM = "pixelSizeZUm";
    private static final String K_SIZE_X = "sizeX";
    private static final String K_SIZE_Y = "sizeY";
    private static final String K_SIZE_Z = "sizeZ";
    private static final String K_PSF_SIZE_X = "psfSizeX";
    private static final String K_PSF_SIZE_Y = "psfSizeY";
    private static final String K_PSF_SIZE_Z = "psfSizeZ";
    private static final String K_TRAILING_BLANK = "trailingBlankSlicePolicy";

    // Immutable artifact identity keys. They are added only by a source-aware writer; config-only
    // consumers preserve them from the recorded full map during their overlay freshness check.
    private static final String K_ARTIFACT_IDENTITY_VERSION = "artifactIdentityVersion";
    private static final String K_SOURCE_CONTENT_HASH = "sourceContentHash";
    private static final String K_SOURCE_SIZE = "sourceSize";
    private static final String K_SOURCE_SERIES_INDEX = "sourceSeriesIndex";

    private DeconvParamsHash() {}

    /**
     * The full 20-key source-independent params map. Both the standalone batch and the setup QC
     * writer delegate here so they cannot drift; the standalone writer then binds its source/series
     * with {@link #withArtifactIdentity}.
     *
     * @param settings          per-channel deconv settings (engine/algorithm/psf/iterations/reg)
     * @param scopeModality      shared scope modality
     * @param na                 numerical aperture
     * @param immersionRi        immersion refractive index
     * @param sampleRi           sample refractive index
     * @param pinholeAiryUnits   confocal pinhole in Airy units (may be {@code null}; only used, and
     *                           then defaulted to 1.0, when {@code scopeModality == CONFOCAL})
     * @param wavelengthNm       this channel's emission wavelength (nm)
     * @param pixelSizeXUm       raw source XY pixel size (µm)
     * @param pixelSizeZUm       raw source Z step (µm)
     * @param sizeX              raw source width (px)
     * @param sizeY              raw source height (px)
     * @param sizeZ              raw source depth (slices)
     */
    public static Map<String, String> buildParams(DeconvSettings settings,
                                                  ScopeModality scopeModality,
                                                  double na,
                                                  double immersionRi,
                                                  double sampleRi,
                                                  Double pinholeAiryUnits,
                                                  double wavelengthNm,
                                                  double pixelSizeXUm,
                                                  double pixelSizeZUm,
                                                  int sizeX,
                                                  int sizeY,
                                                  int sizeZ) {
        Map<String, String> params = new HashMap<String, String>();
        putConfigParams(params, settings, scopeModality, na, immersionRi, sampleRi,
                pinholeAiryUnits, wavelengthNm);
        putGeometryParams(params, pixelSizeXUm, pixelSizeZUm, sizeX, sizeY, sizeZ);
        return params;
    }

    /**
     * The config-derived subset (11 keys) used by the freshness check to detect a parameter change
     * without touching the source image. Identical, key-for-key, to the config portion of
     * {@link #buildParams} — the round-trip test asserts this containment.
     */
    public static Map<String, String> buildConfigParams(DeconvSettings settings,
                                                        ScopeModality scopeModality,
                                                        double na,
                                                        double immersionRi,
                                                        double sampleRi,
                                                        Double pinholeAiryUnits,
                                                        double wavelengthNm) {
        Map<String, String> params = new HashMap<String, String>();
        putConfigParams(params, settings, scopeModality, na, immersionRi, sampleRi,
                pinholeAiryUnits, wavelengthNm);
        return params;
    }

    /**
     * Return a copy of a full parameter map bound to the immutable source/series artifact identity.
     * Display text is deliberately excluded: renaming a series label never changes the scientific
     * identity inputs, while source content or the source-local series index always changes the hash.
     */
    public static Map<String, String> withArtifactIdentity(
            Map<String, String> params,
            DeconvolutionIO.ArtifactIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("Deconvolution artifact identity must not be null.");
        }
        Map<String, String> result = new HashMap<String, String>();
        if (params != null) {
            result.putAll(params);
        }
        result.put(K_ARTIFACT_IDENTITY_VERSION, String.valueOf(identity.version));
        result.put(K_SOURCE_CONTENT_HASH, identity.sourceContentHash);
        result.put(K_SOURCE_SIZE, String.valueOf(identity.sourceSize));
        result.put(K_SOURCE_SERIES_INDEX, String.valueOf(identity.sourceSeriesIndex));
        return result;
    }

    private static void putConfigParams(Map<String, String> params,
                                        DeconvSettings settings,
                                        ScopeModality scopeModality,
                                        double na,
                                        double immersionRi,
                                        double sampleRi,
                                        Double pinholeAiryUnits,
                                        double wavelengthNm) {
        boolean confocal = scopeModality == ScopeModality.CONFOCAL;
        params.put(K_ENGINE, settings == null ? "" : nullToEmpty(settings.engineKey()));
        params.put(K_ALGORITHM, settings == null || settings.algorithm() == null
                ? "" : settings.algorithm().name());
        params.put(K_ITERATIONS, usesIterations(settings)
                ? String.valueOf(settings == null ? 0 : settings.iterations()) : "");
        params.put(K_REGULARIZATION, usesRegularization(settings)
                ? DeconvolutionIO.formatDouble(settings == null ? 0.0 : settings.regularization()) : "");
        params.put(K_PSF_MODEL, settings == null || settings.psfModel() == null
                ? "" : settings.psfModel().name());
        params.put(K_SCOPE_MODALITY, scopeModality == null ? "" : scopeModality.name());
        params.put(K_PINHOLE, confocal
                ? DeconvolutionIO.formatDouble(pinholeAiryUnits == null ? 1.0 : pinholeAiryUnits.doubleValue())
                : "");
        params.put(K_NA, DeconvolutionIO.formatDouble(na));
        params.put(K_IMMERSION_RI, DeconvolutionIO.formatDouble(immersionRi));
        params.put(K_SAMPLE_RI, DeconvolutionIO.formatDouble(sampleRi));
        params.put(K_WAVELENGTH_NM, DeconvolutionIO.formatDouble(wavelengthNm));
    }

    private static void putGeometryParams(Map<String, String> params,
                                          double pixelSizeXUm,
                                          double pixelSizeZUm,
                                          int sizeX,
                                          int sizeY,
                                          int sizeZ) {
        params.put(K_PIXEL_SIZE_X_UM, DeconvolutionIO.formatDouble(pixelSizeXUm));
        params.put(K_PIXEL_SIZE_Z_UM, DeconvolutionIO.formatDouble(pixelSizeZUm));
        params.put(K_SIZE_X, String.valueOf(sizeX));
        params.put(K_SIZE_Y, String.valueOf(sizeY));
        params.put(K_SIZE_Z, String.valueOf(sizeZ));
        params.put(K_PSF_SIZE_X, String.valueOf(psfKernelSize(sizeX, MAX_PSF_SIZE_XY)));
        params.put(K_PSF_SIZE_Y, String.valueOf(psfKernelSize(sizeY, MAX_PSF_SIZE_XY)));
        params.put(K_PSF_SIZE_Z, String.valueOf(psfKernelSize(sizeZ, MAX_PSF_SIZE_Z)));
        params.put(K_TRAILING_BLANK, TRAILING_BLANK_SLICE_POLICY);
    }

    /**
     * Whether the engine/algorithm consume an iteration count. Matches
     * {@code DeconvolutionAnalysis.usesIterations} / {@code CreateBinFileAnalysis.setupUsesIterations}
     * (both resolve the engine via {@link EngineRegistry#byKey} and ask {@code engine.usesIterations}).
     * Defaults to {@code true} for a missing/unknown engine so a stale config never crashes the hash.
     */
    private static boolean usesIterations(DeconvSettings settings) {
        if (settings == null) return true;
        DeconvolutionEngine engine = engineQuietly(settings.engineKey());
        if (engine == null) return true;
        return engine.usesIterations(algorithmOf(settings));
    }

    private static boolean usesRegularization(DeconvSettings settings) {
        if (settings == null) return true;
        DeconvolutionEngine engine = engineQuietly(settings.engineKey());
        if (engine == null) return true;
        return engine.usesRegularization(algorithmOf(settings));
    }

    private static Algorithm algorithmOf(DeconvSettings settings) {
        return settings.algorithm() == null ? Algorithm.RL : settings.algorithm();
    }

    private static DeconvolutionEngine engineQuietly(String engineKey) {
        try {
            return EngineRegistry.byKey(engineKey);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Odd, capped PSF kernel size for an image dimension. Byte-identical to
     * {@code DeconvolutionAnalysis.psfKernelSizeForImageDimension} and
     * {@code CreateBinFileAnalysis.psfKernelSizeForDeconv}, so the hashed {@code psfSize*} equals the
     * real synthesised kernel size.
     */
    private static int psfKernelSize(int imageDimension, int maxOddSize) {
        int capped = Math.min(Math.max(1, imageDimension), Math.max(1, maxOddSize));
        if (capped > 1 && (capped % 2) == 0) {
            capped--;
        }
        return Math.max(1, capped);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
