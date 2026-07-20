package flash.pipeline.objects;

import flash.pipeline.objects.CpcUtils.ObjectInfo;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Object Intensity Profiling engine. For each source object, profiles every partner channel's raw
 * intensity inside the object's (optionally padded) bounding box, centred on the object centroid.
 *
 * <p>Pure and reentrant: no static mutable state, no ImageJ window-manager calls, inputs are not
 * mutated — safe to call from the parallel per-image workers. All geometry is computed once per
 * voxel and shared across partners. Curves are fixed-length on a normalized axis so they average
 * directly across objects.
 */
public final class ObjectIntensityProfiler {

    private ObjectIntensityProfiler() {}

    private static final double TWO_PI = Math.PI * 2.0;

    /**
     * @param sourceLabels  the {@code <source>_objects} label map (defines objects + the mask)
     * @param rawByChannel  partner channel name → raw intensity stack ({@code <ch>_unfiltered})
     * @param sourceObjects objects extracted from {@code sourceLabels} (e.g. {@link CpcUtils#extractObjects})
     * @param sourceChannel name of the source channel (used for PCA sign + within-box correlation)
     * @param cal           voxel calibration (may be null → unit voxels, logged by caller)
     * @param cfg           which profiles to compute and their parameters
     * @return one {@link ObjectProfileResult} per non-empty source object
     */
    public static List<ObjectProfileResult> profile(ImagePlus sourceLabels,
                                                    Map<String, ImagePlus> rawByChannel,
                                                    List<ObjectInfo> sourceObjects,
                                                    String sourceChannel,
                                                    Calibration cal,
                                                    OipConfig cfg) {
        List<ObjectProfileResult> out = new ArrayList<ObjectProfileResult>();
        if (sourceLabels == null || sourceLabels.getStack() == null || sourceObjects == null
                || rawByChannel == null || cfg == null || !cfg.anyProfileEnabled()) {
            return out;
        }

        final ImageStack labelStack = sourceLabels.getStack();
        final int w = sourceLabels.getWidth();
        final int h = sourceLabels.getHeight();
        final int nz = labelStack.getSize();
        final double pw = cal != null && cal.pixelWidth > 0 ? cal.pixelWidth : 1.0;
        final double ph = cal != null && cal.pixelHeight > 0 ? cal.pixelHeight : 1.0;
        final double pd = cal != null && cal.pixelDepth > 0 ? cal.pixelDepth : 1.0;

        // Stable partner ordering.
        List<String> partners = new ArrayList<String>(rawByChannel.keySet());

        for (ObjectInfo obj : sourceObjects) {
            if (obj == null || obj.isBoxEmpty()) continue;
            ObjectProfileResult r = profileOne(obj, sourceLabels, labelStack, rawByChannel, partners,
                    sourceChannel, w, h, nz, pw, ph, pd, cfg);
            if (r != null) out.add(r);
        }
        return out;
    }

    private static ObjectProfileResult profileOne(ObjectInfo obj, ImagePlus sourceLabels,
                                                  ImageStack labelStack, Map<String, ImagePlus> rawByChannel,
                                                  List<String> partners, String sourceChannel,
                                                  int w, int h, int nz, double pw, double ph, double pd,
                                                  OipConfig cfg) {
        // Padded, clamped box.
        int extX = obj.xmax - obj.xmin + 1, extY = obj.ymax - obj.ymin + 1, extZ = obj.zmax - obj.zmin + 1;
        int padX = (int) Math.round(cfg.boxPadPct / 100.0 * extX);
        int padY = (int) Math.round(cfg.boxPadPct / 100.0 * extY);
        int padZ = (int) Math.round(cfg.boxPadPct / 100.0 * extZ);
        int x0 = Math.max(0, obj.xmin - padX), x1 = Math.min(w - 1, obj.xmax + padX);
        int y0 = Math.max(0, obj.ymin - padY), y1 = Math.min(h - 1, obj.ymax + padY);
        int z0 = Math.max(0, obj.zmin - padZ), z1 = Math.min(nz - 1, obj.zmax + padZ);
        if (x1 < x0 || y1 < y0 || z1 < z0) return null;

        final double cx = obj.cx, cy = obj.cy, cz = obj.cz;

        // Per-side normalization denominators for the marginal axes (calibration cancels).
        final double dnXneg = Math.max(1e-9, cx - x0), dnXpos = Math.max(1e-9, x1 - cx);
        final double dnYneg = Math.max(1e-9, cy - y0), dnYpos = Math.max(1e-9, y1 - cy);
        final double dnZneg = Math.max(1e-9, cz - z0), dnZpos = Math.max(1e-9, z1 - cz);

        // rMax = max calibrated corner distance from the centroid.
        double rMax = 0.0;
        for (int xi = 0; xi < 2; xi++)
            for (int yi = 0; yi < 2; yi++)
                for (int zi = 0; zi < 2; zi++) {
                    double ddx = ((xi == 0 ? x0 : x1) - cx) * pw;
                    double ddy = ((yi == 0 ? y0 : y1) - cy) * ph;
                    double ddz = ((zi == 0 ? z0 : z1) - cz) * pd;
                    rMax = Math.max(rMax, Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz));
                }
        if (rMax <= 0) rMax = 1.0;

        ObjectProfileResult res = new ObjectProfileResult(sourceChannel, obj.label, obj.voxelCount);
        res.rMax = rMax;

        // --- Principal axes (PCA over the object's own voxels), if requested. ---
        double[] e1 = {1, 0, 0}, e2 = {0, 1, 0}, e3 = {0, 0, 1};
        // Start at 0 so the corner-projection max gives the true half-extent (initialising to 1 would
        // under-normalize principal-axis bins for objects whose calibrated extent is below 1 unit).
        double halfMaj = 0, halfMin = 0, halfThr = 0;
        if (cfg.doPrincipalAxis) {
            double cxx = 0, cyy = 0, czz = 0, cxy = 0, cxz = 0, cyz = 0;
            long nObj = 0;
            for (int z = z0; z <= z1; z++) {
                ImageProcessor lp = labelStack.getProcessor(z + 1);
                double dz = (z - cz) * pd;
                for (int y = y0; y <= y1; y++) {
                    double dy = (y - cy) * ph;
                    for (int x = x0; x <= x1; x++) {
                        if (label(lp.getf(x, y)) != obj.label) continue;
                        double dx = (x - cx) * pw;
                        cxx += dx * dx; cyy += dy * dy; czz += dz * dz;
                        cxy += dx * dy; cxz += dx * dz; cyz += dy * dz;
                        nObj++;
                    }
                }
            }
            if (nObj > 0) {
                SymmetricEigen3 eig = SymmetricEigen3.of(cxx / nObj, cyy / nObj, czz / nObj,
                        cxy / nObj, cxz / nObj, cyz / nObj);
                res.eigenvalues = eig.values;
                res.eigenvectors = eig.vectors;
                e1 = col(eig.vectors, 0); e2 = col(eig.vectors, 1); e3 = col(eig.vectors, 2);
                double l1 = eig.values[0], l2 = eig.values[1], l3 = eig.values[2];
                res.elongation = l2 > 1e-12 ? Math.sqrt(l1 / l2) : Double.NaN;
                res.flatness = l3 > 1e-12 ? Math.sqrt(l2 / l3) : Double.NaN;
                // Half-extents = max abs projection of the box corners onto each axis.
                for (int xi = 0; xi < 2; xi++)
                    for (int yi = 0; yi < 2; yi++)
                        for (int zi = 0; zi < 2; zi++) {
                            double ddx = ((xi == 0 ? x0 : x1) - cx) * pw;
                            double ddy = ((yi == 0 ? y0 : y1) - cy) * ph;
                            double ddz = ((zi == 0 ? z0 : z1) - cz) * pd;
                            halfMaj = Math.max(halfMaj, Math.abs(dot(ddx, ddy, ddz, e1)));
                            halfMin = Math.max(halfMin, Math.abs(dot(ddx, ddy, ddz, e2)));
                            halfThr = Math.max(halfThr, Math.abs(dot(ddx, ddy, ddz, e3)));
                        }
            }
        }

        // --- Per-partner accumulators. ---
        boolean needSource = cfg.doWithinBox || cfg.doPrincipalAxis;
        ImageProcessor[] partnerProc = new ImageProcessor[partners.size()];
        PAcc[] acc = new PAcc[partners.size()];
        for (int p = 0; p < partners.size(); p++) acc[p] = new PAcc(cfg);
        int srcIdx = partners.indexOf(sourceChannel);
        double[] srcSkew = new double[3]; // intensity-weighted source projection sign per PC axis

        // --- Main pass over region voxels. ---
        for (int z = z0; z <= z1; z++) {
            ImageProcessor lp = labelStack.getProcessor(z + 1);
            for (int p = 0; p < partners.size(); p++) {
                ImagePlus ip = rawByChannel.get(partners.get(p));
                partnerProc[p] = ip != null && ip.getStack() != null && z < ip.getStack().getSize()
                        ? ip.getStack().getProcessor(z + 1) : null;
            }
            double dz = (z - cz) * pd;
            for (int y = y0; y <= y1; y++) {
                double dy = (y - cy) * ph;
                for (int x = x0; x <= x1; x++) {
                    int lab = label(lp.getf(x, y));
                    if (cfg.region == OipConfig.Region.OBJECT_VOXELS && lab != obj.label) continue;

                    double dx = (x - cx) * pw;
                    double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double rNorm = r / rMax;
                    int radialBin = bin(rNorm, cfg.radialBins);
                    int shellBin = bin(rNorm, cfg.shells);
                    double xNorm = (x - cx) < 0 ? (x - cx) / dnXneg : (x - cx) / dnXpos;
                    double yNorm = (y - cy) < 0 ? (y - cy) / dnYneg : (y - cy) / dnYpos;
                    double zNorm = (z - cz) < 0 ? (z - cz) / dnZneg : (z - cz) / dnZpos;
                    int mxBin = binSigned(xNorm, cfg.resampleN);
                    int myBin = binSigned(yNorm, cfg.resampleN);
                    int mzBin = binSigned(zNorm, cfg.resampleN);
                    double theta = Math.atan2(dy, dx);
                    if (theta < 0) theta += TWO_PI;
                    int angBin = bin(theta / TWO_PI, cfg.angularBins);
                    double proj1 = 0, proj2 = 0, proj3 = 0;
                    int pcMajBin = 0, pcMinBin = 0, pcThrBin = 0;
                    if (cfg.doPrincipalAxis) {
                        proj1 = dot(dx, dy, dz, e1);
                        proj2 = dot(dx, dy, dz, e2);
                        proj3 = dot(dx, dy, dz, e3);
                        pcMajBin = binSigned(proj1 / Math.max(1e-9, halfMaj), cfg.resampleN);
                        pcMinBin = binSigned(proj2 / Math.max(1e-9, halfMin), cfg.resampleN);
                        pcThrBin = binSigned(proj3 / Math.max(1e-9, halfThr), cfg.resampleN);
                    }

                    double srcV = 0;
                    boolean srcFinite = false;
                    if (needSource && srcIdx >= 0 && partnerProc[srcIdx] != null) {
                        srcV = partnerProc[srcIdx].getf(x, y);
                        srcFinite = Double.isFinite(srcV);
                        if (cfg.doPrincipalAxis && srcFinite) {
                            srcSkew[0] += srcV * proj1;
                            srcSkew[1] += srcV * proj2;
                            srcSkew[2] += srcV * proj3;
                        }
                    }

                    for (int p = 0; p < partners.size(); p++) {
                        ImageProcessor pp = partnerProc[p];
                        if (pp == null) continue;
                        double v = pp.getf(x, y);
                        if (!Double.isFinite(v)) continue; // a single NaN/Inf pixel must not poison stats
                        PAcc a = acc[p];
                        a.stat(v);
                        if (cfg.doRadial) { a.radial.add(radialBin, v); a.sumV += v; a.sumVdx += v * dx; a.sumVdy += v * dy; a.sumVdz += v * dz; }
                        if (cfg.doMarginal) { a.mx.add(mxBin, v); a.my.add(myBin, v); a.mz.add(mzBin, v); }
                        if (cfg.doAngular) a.ang.add(angBin, v);
                        if (cfg.doShell) a.shell.add(shellBin, v);
                        if (cfg.doPrincipalAxis) {
                            a.pcMaj.add(pcMajBin, v); a.pcMin.add(pcMinBin, v); a.pcThr.add(pcThrBin, v);
                            a.pcMajProj += v * proj1; a.pcW += v;
                        }
                        if (cfg.doWithinBox && srcFinite) {
                            a.sA += srcV; a.sB += v; a.sAA += srcV * srcV; a.sBB += v * v; a.sAB += srcV * v; a.nAB++;
                        }
                    }
                }
            }
        }

        // PC sign convention: flip axes where the source skews negative (keeps polarity comparable).
        boolean[] flip = {srcSkew[0] < 0, srcSkew[1] < 0, srcSkew[2] < 0};

        for (int p = 0; p < partners.size(); p++) {
            finalizePartner(res.partner(partners.get(p)), acc[p], cfg, rMax, halfMaj, flip);
        }
        return res;
    }

    private static void finalizePartner(ObjectProfileResult.PartnerProfiles pf, PAcc a, OipConfig cfg,
                                        double rMax, double halfMaj, boolean[] flip) {
        if (cfg.doRadial) {
            pf.radialRaw = a.radial.means();
            pf.radialNorm = normalize(pf.radialRaw, a, cfg);
            pf.radialCoreEdge = coreEdge(pf.radialRaw);
            pf.radialPeakR = peakPos(pf.radialRaw);
            double sv = a.sumV;
            if (sv > 0) {
                double ox = a.sumVdx / sv, oy = a.sumVdy / sv, oz = a.sumVdz / sv;
                pf.radialPolarity = Math.sqrt(ox * ox + oy * oy + oz * oz) / rMax;
            }
        }
        if (cfg.doMarginal) {
            pf.marginalXRaw = a.mx.means(); pf.marginalXNorm = normalize(pf.marginalXRaw, a, cfg);
            pf.marginalYRaw = a.my.means(); pf.marginalYNorm = normalize(pf.marginalYRaw, a, cfg);
            pf.marginalZRaw = a.mz.means(); pf.marginalZNorm = normalize(pf.marginalZRaw, a, cfg);
        }
        if (cfg.doAngular) {
            pf.angularRaw = a.ang.means(); pf.angularNorm = normalize(pf.angularRaw, a, cfg);
            pf.ringCompleteness = ringCompleteness(pf.angularRaw, cfg.ringThresholdPct);
            pf.angularUniformity = uniformity(pf.angularRaw);
        }
        if (cfg.doShell) {
            double[] sh = a.shell.means();
            pf.shellInner = sh.length > 0 ? sh[0] : Double.NaN;
            pf.shellOuter = sh.length > 0 ? sh[sh.length - 1] : Double.NaN;
            pf.shellMid = sh.length > 0 ? sh[sh.length / 2] : Double.NaN;
        }
        if (cfg.doPrincipalAxis) {
            pf.pcMajorRaw = maybeReverse(a.pcMaj.means(), flip[0]);
            pf.pcMinorRaw = maybeReverse(a.pcMin.means(), flip[1]);
            pf.pcThirdRaw = maybeReverse(a.pcThr.means(), flip[2]);
            pf.pcMajorNorm = normalize(pf.pcMajorRaw, a, cfg);
            pf.pcMinorNorm = normalize(pf.pcMinorRaw, a, cfg);
            pf.pcThirdNorm = normalize(pf.pcThirdRaw, a, cfg);
            if (a.pcW > 0) {
                double pol = (a.pcMajProj / a.pcW) / Math.max(1e-9, halfMaj);
                pf.pcMajorPolarity = flip[0] ? -pol : pol;
            }
        }
        if (cfg.doWithinBox && a.nAB > 0) {
            double n = a.nAB;
            double cov = a.sAB - a.sA * a.sB / n;
            double va = a.sAA - a.sA * a.sA / n;
            double vb = a.sBB - a.sB * a.sB / n;
            pf.withinBoxPearson = (va > 0 && vb > 0) ? cov / Math.sqrt(va * vb) : Double.NaN;
            double denom = Math.sqrt(a.sAA * a.sBB);
            pf.withinBoxManders = denom > 0 ? a.sAB / denom : Double.NaN; // Manders' overlap coefficient
        }
    }

    // --- normalization + scalar helpers ---

    private static double[] normalize(double[] raw, PAcc a, OipConfig cfg) {
        if (raw == null) return null;
        double[] out = new double[raw.length];
        double min = a.min, max = a.max, mean = a.count > 0 ? a.sum / a.count : 0;
        double sd = a.count > 0 ? Math.sqrt(Math.max(0, a.sumsq / a.count - mean * mean)) : 0;
        for (int i = 0; i < raw.length; i++) {
            double v = raw[i];
            if (Double.isNaN(v)) { out[i] = Double.NaN; continue; }
            switch (cfg.intensityNorm) {
                case PER_OBJECT_MINMAX: out[i] = max > min ? (v - min) / (max - min) : 0; break;
                case DIVIDE_BY_MEAN:    out[i] = mean != 0 ? v / mean : 0; break;
                case ZSCORE:            out[i] = sd > 0 ? (v - mean) / sd : 0; break;
                default:                out[i] = v;
            }
        }
        return out;
    }

    private static double coreEdge(double[] m) {
        int n = m.length;
        double inner = meanRange(m, 0, n / 3);
        double outer = meanRange(m, 2 * n / 3, n);
        return outer > 0 ? inner / outer : Double.NaN;
    }

    private static double peakPos(double[] m) {
        int best = -1; double bv = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < m.length; i++) if (!Double.isNaN(m[i]) && m[i] > bv) { bv = m[i]; best = i; }
        return best < 0 ? Double.NaN : (best + 0.5) / m.length;
    }

    private static double ringCompleteness(double[] m, double thresholdPct) {
        double max = 0;
        for (double v : m) if (!Double.isNaN(v) && v > max) max = v;
        if (max <= 0) return Double.NaN;
        double cut = thresholdPct / 100.0 * max;
        int above = 0;
        for (double v : m) if (!Double.isNaN(v) && v >= cut) above++;
        return (double) above / m.length;
    }

    private static double uniformity(double[] m) {
        double sum = 0; int n = 0;
        for (double v : m) if (!Double.isNaN(v)) { sum += v; n++; }
        if (n == 0) return Double.NaN;
        double mean = sum / n;
        if (mean <= 0) return Double.NaN;
        double ss = 0;
        for (double v : m) if (!Double.isNaN(v)) ss += (v - mean) * (v - mean);
        double cv = Math.sqrt(ss / n) / mean;
        return Math.max(0, 1 - cv);
    }

    private static double meanRange(double[] m, int from, int to) {
        double s = 0; int n = 0;
        for (int i = Math.max(0, from); i < Math.min(m.length, to); i++)
            if (!Double.isNaN(m[i])) { s += m[i]; n++; }
        return n > 0 ? s / n : 0;
    }

    private static double[] maybeReverse(double[] a, boolean rev) {
        if (a == null || !rev) return a;
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[a.length - 1 - i];
        return out;
    }

    // --- binning ---

    /** Map a [0,1] value to [0,bins). */
    private static int bin(double t, int bins) {
        int b = (int) Math.floor(t * bins);
        if (b < 0) return 0;
        if (b >= bins) return bins - 1;
        return b;
    }

    /** Map a [-1,1] value to [0,bins). */
    private static int binSigned(double t, int bins) {
        return bin((t + 1.0) * 0.5, bins);
    }

    private static double dot(double x, double y, double z, double[] e) {
        return x * e[0] + y * e[1] + z * e[2];
    }

    private static double[] col(double[][] m, int k) {
        return new double[] {m[0][k], m[1][k], m[2][k]};
    }

    private static int label(float v) {
        if (!Float.isFinite(v) || v <= 0f) return 0;
        return v > Integer.MAX_VALUE ? 0 : Math.round(v);
    }

    /** Per-partner running accumulators for one object. */
    private static final class PAcc {
        final Bins radial, mx, my, mz, pcMaj, pcMin, pcThr, ang, shell;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0, sumsq = 0;
        long count = 0;
        double sumV = 0, sumVdx = 0, sumVdy = 0, sumVdz = 0;     // radial polarity
        double pcMajProj = 0, pcW = 0;                           // pc-major polarity
        double sA = 0, sB = 0, sAA = 0, sBB = 0, sAB = 0; long nAB = 0; // within-box (A=source,B=partner)

        PAcc(OipConfig cfg) {
            radial = new Bins(cfg.radialBins);
            mx = new Bins(cfg.resampleN); my = new Bins(cfg.resampleN); mz = new Bins(cfg.resampleN);
            pcMaj = new Bins(cfg.resampleN); pcMin = new Bins(cfg.resampleN); pcThr = new Bins(cfg.resampleN);
            ang = new Bins(cfg.angularBins);
            shell = new Bins(Math.max(1, cfg.shells));
        }

        void stat(double v) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v; sumsq += v * v; count++;
        }
    }

    /** Fixed-length sum/count bins; {@code means()} returns NaN for empty bins. */
    private static final class Bins {
        final double[] sum; final int[] cnt;
        Bins(int n) { sum = new double[Math.max(1, n)]; cnt = new int[Math.max(1, n)]; }
        void add(int i, double v) {
            if (i < 0) i = 0; else if (i >= sum.length) i = sum.length - 1;
            sum[i] += v; cnt[i]++;
        }
        double[] means() {
            double[] m = new double[sum.length];
            for (int i = 0; i < sum.length; i++) m[i] = cnt[i] > 0 ? sum[i] / cnt[i] : Double.NaN;
            return m;
        }
    }
}
