package flash.pipeline.analyses;

import flash.pipeline.analyses.StatisticsConfig.DistributionMode;
import flash.pipeline.analyses.StatisticsConfig.PairedMode;
import flash.pipeline.analyses.StatisticsConfig.PostHocMethod;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Defaults + enum parsing for {@link StatisticsConfig}.
 * Also ensures CLI-style aliases (case-insensitive, hyphen/underscore agnostic)
 * round-trip to the right enum.
 */
public class StatisticsConfigTest {

    @Test
    public void defaultsReproduceLegacyEngineBehaviour() {
        StatisticsConfig cfg = new StatisticsConfig();
        assertEquals(PairedMode.OFF, cfg.pairedMode);
        assertEquals(DistributionMode.AUTO, cfg.distributionMode);
        assertEquals(PostHocMethod.BONFERRONI, cfg.postHocMethod);
        assertNull(cfg.metricFilter);
    }

    @Test
    public void pairedMode_parsesCanonicalNames() {
        assertEquals(PairedMode.OFF,        PairedMode.parse("off",        PairedMode.HEMISPHERE));
        assertEquals(PairedMode.HEMISPHERE, PairedMode.parse("HEMISPHERE", PairedMode.OFF));
        assertEquals(PairedMode.REGION,     PairedMode.parse("region",     PairedMode.OFF));
        assertEquals(PairedMode.SESSION,    PairedMode.parse("Session",    PairedMode.OFF));
    }

    @Test
    public void pairedMode_aliasesAndPunctuationNormalised() {
        assertEquals(PairedMode.HEMISPHERE, PairedMode.parse("hemi",         PairedMode.OFF));
        assertEquals(PairedMode.HEMISPHERE, PairedMode.parse("Hemi",         PairedMode.OFF));
        assertEquals(PairedMode.OFF,        PairedMode.parse("unpaired",     PairedMode.HEMISPHERE));
        assertEquals(PairedMode.OFF,        PairedMode.parse("none",         PairedMode.HEMISPHERE));
        assertEquals(PairedMode.SESSION,    PairedMode.parse(" session ",    PairedMode.OFF));
    }

    @Test
    public void pairedMode_unknownReturnsFallback() {
        assertEquals(PairedMode.HEMISPHERE, PairedMode.parse("",       PairedMode.HEMISPHERE));
        assertEquals(PairedMode.HEMISPHERE, PairedMode.parse(null,     PairedMode.HEMISPHERE));
        assertEquals(PairedMode.HEMISPHERE, PairedMode.parse("garbage", PairedMode.HEMISPHERE));
    }

    @Test
    public void distributionMode_parsesCanonicalNames() {
        assertEquals(DistributionMode.AUTO,            DistributionMode.parse("auto", DistributionMode.AUTO));
        assertEquals(DistributionMode.ASSUME_NORMAL,   DistributionMode.parse("ASSUME_NORMAL", DistributionMode.AUTO));
        assertEquals(DistributionMode.ASSUME_SKEWED,   DistributionMode.parse("assume-skewed", DistributionMode.AUTO));
    }

    @Test
    public void distributionMode_aliasesNormalised() {
        assertEquals(DistributionMode.ASSUME_NORMAL, DistributionMode.parse("normal",         DistributionMode.AUTO));
        assertEquals(DistributionMode.ASSUME_NORMAL, DistributionMode.parse("Parametric",     DistributionMode.AUTO));
        assertEquals(DistributionMode.ASSUME_SKEWED, DistributionMode.parse("skewed",         DistributionMode.AUTO));
        assertEquals(DistributionMode.ASSUME_SKEWED, DistributionMode.parse("non-parametric", DistributionMode.AUTO));
        assertEquals(DistributionMode.ASSUME_SKEWED, DistributionMode.parse("nonparametric",  DistributionMode.AUTO));
    }

    @Test
    public void distributionMode_unknownReturnsFallback() {
        assertEquals(DistributionMode.AUTO, DistributionMode.parse("",       DistributionMode.AUTO));
        assertEquals(DistributionMode.AUTO, DistributionMode.parse(null,     DistributionMode.AUTO));
        assertEquals(DistributionMode.AUTO, DistributionMode.parse("rubbish", DistributionMode.AUTO));
    }

    @Test
    public void postHocMethod_parsesCanonicalNames() {
        assertEquals(PostHocMethod.BONFERRONI, PostHocMethod.parse("bonferroni", PostHocMethod.NONE));
        assertEquals(PostHocMethod.TUKEY,      PostHocMethod.parse("TUKEY",      PostHocMethod.NONE));
        assertEquals(PostHocMethod.DUNNS,      PostHocMethod.parse("dunns",      PostHocMethod.NONE));
        assertEquals(PostHocMethod.NONE,       PostHocMethod.parse("none",       PostHocMethod.BONFERRONI));
    }

    @Test
    public void postHocMethod_aliasesNormalised() {
        assertEquals(PostHocMethod.TUKEY, PostHocMethod.parse("Tukey HSD",  PostHocMethod.NONE));
        assertEquals(PostHocMethod.TUKEY, PostHocMethod.parse("hsd",        PostHocMethod.NONE));
        assertEquals(PostHocMethod.TUKEY, PostHocMethod.parse("tukey-hsd",  PostHocMethod.NONE));
        assertEquals(PostHocMethod.DUNNS, PostHocMethod.parse("Dunn's",     PostHocMethod.NONE));
        assertEquals(PostHocMethod.DUNNS, PostHocMethod.parse("Dunn",       PostHocMethod.NONE));
        assertEquals(PostHocMethod.DUNNS, PostHocMethod.parse("dunns_test", PostHocMethod.NONE));
    }

    @Test
    public void postHocMethod_unknownReturnsFallback() {
        assertEquals(PostHocMethod.BONFERRONI, PostHocMethod.parse("",       PostHocMethod.BONFERRONI));
        assertEquals(PostHocMethod.BONFERRONI, PostHocMethod.parse(null,     PostHocMethod.BONFERRONI));
        assertEquals(PostHocMethod.BONFERRONI, PostHocMethod.parse("scheffe", PostHocMethod.BONFERRONI));
    }

    @Test
    public void allEnumsRoundTripThroughName() {
        for (PairedMode m : PairedMode.values()) {
            assertEquals(m, PairedMode.parse(m.name(), PairedMode.OFF));
        }
        for (DistributionMode m : DistributionMode.values()) {
            assertEquals(m, DistributionMode.parse(m.name(), DistributionMode.AUTO));
        }
        for (PostHocMethod m : PostHocMethod.values()) {
            assertEquals(m, PostHocMethod.parse(m.name(), PostHocMethod.BONFERRONI));
        }
    }
}
