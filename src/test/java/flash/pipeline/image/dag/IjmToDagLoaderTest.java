package flash.pipeline.image.dag;

import flash.pipeline.image.NamedFilterLoader;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class IjmToDagLoaderTest {

    @Test
    public void bundledPresetsSeedSingleLineDag() {
        for (String preset : NamedFilterLoader.FILTER_NAMES) {
            String macro = NamedFilterLoader.loadFilterContent(preset);
            assertNotNull("macro for " + preset, macro);

            DagIR dag = IjmToDagLoader.load(macro);

            assertNotNull("dag for " + preset, dag);
            assertFalse("lines for " + preset, dag.lines.isEmpty());
            assertNotNull("output for " + preset, dag.output);
        }
    }
}
