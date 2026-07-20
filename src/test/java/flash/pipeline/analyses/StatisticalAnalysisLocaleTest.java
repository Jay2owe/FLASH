package flash.pipeline.analyses;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class StatisticalAnalysisLocaleTest {

    @Test
    public void fmtStat_usesDotDecimalUnderGermanLocale() throws Exception {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertEquals("1.500000", invokeFormatter("fmtStat", 1.5));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void fmtP_usesScientificNotationWithDotDecimalUnderGermanLocale() throws Exception {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertEquals("1.23e-05", invokeFormatter("fmtP", 0.0000123));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void fmtP_usesFixedDecimalWithDotDecimalUnderGermanLocale() throws Exception {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertEquals("0.123457", invokeFormatter("fmtP", 0.1234567));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void fmtStatAndFmtP_preserveBlankOutputForNonFiniteValues() throws Exception {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertEquals("", invokeFormatter("fmtStat", Double.NaN));
            assertEquals("", invokeFormatter("fmtStat", Double.POSITIVE_INFINITY));
            assertEquals("", invokeFormatter("fmtP", Double.NaN));
            assertEquals("", invokeFormatter("fmtP", Double.POSITIVE_INFINITY));
        } finally {
            Locale.setDefault(original);
        }
    }

    private static String invokeFormatter(String methodName, double value) throws Exception {
        Method method = StatisticalAnalysis.class.getDeclaredMethod(methodName, double.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }
}
