package flash.pipeline.image;

import org.junit.Test;
import static org.junit.Assert.*;

public class NamedFilterLoaderTest {

    /**
     * Regression: loadIntensityFilter() returned null when the Fiji classloader
     * could not resolve the JAR resource, causing Intensity Analysis to abort
     * with "Intensity filter resource not found in JAR". The fix adds a hardcoded
     * fallback so the method never returns null.
     */
    @Test
    public void loadIntensityFilter_should_never_return_null() {
        String result = NamedFilterLoader.loadIntensityFilter();
        assertNotNull("loadIntensityFilter must never return null (hardcoded fallback)", result);
        assertTrue("filter must contain Median command", result.contains("Median"));
        assertTrue("filter must contain Subtract Background command", result.contains("Subtract Background"));
    }

    @Test
    public void loadDefaultFilter_should_never_return_null() {
        String result = NamedFilterLoader.loadDefaultFilter();
        assertNotNull("loadDefaultFilter must never return null", result);
    }
}
