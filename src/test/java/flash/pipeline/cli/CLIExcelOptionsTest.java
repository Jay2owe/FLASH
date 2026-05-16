package flash.pipeline.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CLIExcelOptionsTest {

    @Test
    public void parsesAndSerializesTextureFeatureExportFlag() {
        CLIConfig parsed = CLIArgumentParser.parse("dir=[/tmp/data] "
                + "excel.texture.features=true");

        assertTrue(parsed.getSelectedAnalyses()[10]);
        assertEquals(Boolean.TRUE, parsed.getExcel().getIncludeTextureFeatures());

        CLIConfig reparsed = CLIArgumentParser.parse(CLIArgumentParser.serialize(parsed));
        assertEquals(Boolean.TRUE, reparsed.getExcel().getIncludeTextureFeatures());
    }
}
