package flash.pipeline.image;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FilterMacroEditorModelTest {

    @Test
    public void parseSimpleRunMacro_exposesEditableParametersAndRendersUpdates() {
        String macro =
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n" +
                "run(\"Subtract Background...\", \"rolling=20 stack\");\n" +
                "run(\"Median...\", \"radius=2 stack\");";

        FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(macro);
        assertTrue(definition.hasEditableParameters());
        assertEquals(1, definition.getSections().size());
        assertEquals("Filter Steps", definition.getSections().get(0).title);
        assertEquals("Gaussian Blur, Subtract Background, Median", definition.getSections().get(0).summary);

        List<FilterMacroEditorModel.Entry> entries = definition.getSections().get(0).entries;
        assertEquals(3, entries.size());
        assertEquals("Gaussian Blur", entries.get(0).label);
        assertEquals("sigma", entries.get(0).parameters.get(0).key);
        assertEquals("2", entries.get(0).parameters.get(0).getValue());

        entries.get(0).parameters.get(0).setValue("4");
        entries.get(1).parameters.get(0).setValue("30");
        entries.get(2).parameters.get(0).setValue("3");

        assertEquals(
                "run(\"Gaussian Blur...\", \"sigma=4 stack\");\n" +
                "run(\"Subtract Background...\", \"rolling=30 stack\");\n" +
                "run(\"Median...\", \"radius=3 stack\");",
                definition.render());
    }

    @Test
    public void parsePunctaResolveMacro_buildsSectionHeadersFromComments() {
        String macro =
                "original = getTitle();\n" +
                "\n" +
                "// === DENSITY PATH ===\n" +
                "run(\"Subtract Background...\", \"rolling=50 stack\");\n" +
                "run(\"Gaussian Blur 3D...\", \"x=2 y=2 z=1\");\n" +
                "run(\"Auto Local Threshold\", \"method=Bernsen radius=15 parameter_1=0 parameter_2=0 white stack\");\n" +
                "run(\"Gaussian Blur 3D...\", \"x=8 y=8 z=2\");\n" +
                "\n" +
                "// === EDGE PATH (from raw) ===\n" +
                "run(\"Auto Local Threshold\", \"method=Bernsen radius=15 parameter_1=0 parameter_2=0 white stack\");\n" +
                "run(\"Gaussian Blur 3D...\", \"x=2 y=2 z=1\");\n" +
                "run(\"Variance...\", \"radius=5 stack\");\n" +
                "\n" +
                "// === COMBINE + POST-PROCESS ===\n" +
                "run(\"Gaussian Blur...\", \"sigma=3 stack\");\n" +
                "run(\"Minimum 3D...\", \"x=4 y=4 z=1\");";

        FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(macro);
        assertEquals(3, definition.getSections().size());
        assertEquals("DENSITY PATH", definition.getSections().get(0).title);
        assertEquals("Subtract Background, Gaussian Blur 3D, Bernsen, Gaussian Blur 3D",
                definition.getSections().get(0).summary);
        assertEquals("EDGE PATH", definition.getSections().get(1).title);
        assertEquals("Bernsen, Gaussian Blur 3D, Variance",
                definition.getSections().get(1).summary);
        assertEquals("COMBINE", definition.getSections().get(2).title);
        assertEquals("Gaussian Blur, Minimum 3D",
                definition.getSections().get(2).summary);

        FilterMacroEditorModel.Entry firstDensityOp = definition.getSections().get(0).entries.get(1);
        assertEquals("Gaussian Blur 3D", firstDensityOp.label);
        assertEquals("x", firstDensityOp.parameters.get(0).key);
        assertEquals("2", firstDensityOp.parameters.get(0).getValue());
        assertEquals("z", firstDensityOp.parameters.get(2).key);
        assertEquals("1", firstDensityOp.parameters.get(2).getValue());
    }

    @Test
    public void parseAssignmentBlock_exposesVariablesAsEditableParameters() {
        String macro =
                "// ---- PARAMETERS ----\n" +
                "small_xy = 2;\n" +
                "small_z = 1;\n" +
                "big_xy = 15;\n" +
                "big_z = 4;\n" +
                "\n" +
                "// Step 1: 3D Difference of Gaussians\n" +
                "run(\"Gaussian Blur 3D...\", \"x=\" + small_xy + \" y=\" + small_xy + \" z=\" + small_z);";

        FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(macro);
        assertEquals(1, definition.getSections().size());
        assertEquals("PARAMETERS", definition.getSections().get(0).title);
        assertEquals("Small XY, Small Z, Big XY, Big Z", definition.getSections().get(0).summary);

        List<FilterMacroEditorModel.Entry> entries = definition.getSections().get(0).entries;
        entries.get(0).parameters.get(0).setValue("3");
        entries.get(1).parameters.get(0).setValue("2");

        assertEquals(
                "// ---- PARAMETERS ----\n" +
                "small_xy = 3;\n" +
                "small_z = 2;\n" +
                "big_xy = 15;\n" +
                "big_z = 4;\n" +
                "\n" +
                "// Step 1: 3D Difference of Gaussians\n" +
                "run(\"Gaussian Blur 3D...\", \"x=\" + small_xy + \" y=\" + small_xy + \" z=\" + small_z);",
                definition.render());
    }

    @Test
    public void unmutatedParseRender_isByteIdenticalForBundledPresets() throws IOException {
        File[] presets = bundledPresetFiles();
        assertTrue(presets.length > 0);
        for (int i = 0; i < presets.length; i++) {
            byte[] original = Files.readAllBytes(presets[i].toPath());
            String macro = new String(original, StandardCharsets.UTF_8);
            String rendered = FilterMacroEditorModel.parse(macro).render();
            assertArrayEquals(presets[i].getName(), original, rendered.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void addRunEntry_appendsAndRendersParseableCommand() {
        String macro = "run(\"Median...\", \"radius=2 stack\");";

        FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(macro);
        FilterMacroEditorModel.Section section = definition.getSections().get(0);
        definition.addRunEntry(section, "Gaussian Blur...",
                Arrays.asList(new FilterMacroEditorModel.Parameter("sigma", "2", "2", "", "")));

        String rendered = definition.render();
        assertTrue(rendered.contains("run(\"Gaussian Blur...\", \"sigma=2 stack\");"));

        FilterMacroEditorModel.MacroDefinition reparsed = FilterMacroEditorModel.parse(rendered);
        List<FilterMacroEditorModel.Entry> entries = reparsed.getSections().get(0).entries;
        assertEquals(2, entries.size());
        assertEquals("Gaussian Blur", entries.get(1).label);
        assertEquals("sigma", entries.get(1).parameters.get(0).key);
        assertEquals("2", entries.get(1).parameters.get(0).getValue());
    }

    @Test
    public void removeEntry_removesFromRenderOutput() {
        String macro =
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n" +
                "run(\"Median...\", \"radius=2 stack\");";

        FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(macro);
        definition.removeEntry(definition.getSections().get(0).entries.get(0));

        String rendered = definition.render();
        assertFalse(rendered.contains("Gaussian Blur"));
        assertTrue(rendered.contains("Median"));

        FilterMacroEditorModel.MacroDefinition reparsed = FilterMacroEditorModel.parse(rendered);
        assertEquals(1, reparsed.getSections().get(0).entries.size());
        assertEquals("Median", reparsed.getSections().get(0).entries.get(0).label);
    }

    @Test
    public void moveEntry_changesLineOrder() {
        String macro =
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n" +
                "run(\"Subtract Background...\", \"rolling=20 stack\");\n" +
                "run(\"Median...\", \"radius=2 stack\");";

        FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(macro);
        FilterMacroEditorModel.Entry median = definition.getSections().get(0).entries.get(2);
        definition.moveEntry(median, 0);

        FilterMacroEditorModel.MacroDefinition reparsed = FilterMacroEditorModel.parse(definition.render());
        List<FilterMacroEditorModel.Entry> entries = reparsed.getSections().get(0).entries;
        assertEquals("Median", entries.get(0).label);
        assertEquals("Gaussian Blur", entries.get(1).label);
        assertEquals("Subtract Background", entries.get(2).label);
    }

    @Test
    public void moveEntryToSection_movesBetweenSections() {
        String macro =
                "// === FIRST ===\n" +
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n" +
                "\n" +
                "// === SECOND ===\n" +
                "run(\"Median...\", \"radius=2 stack\");";

        FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(macro);
        FilterMacroEditorModel.Entry gaussian = definition.getSections().get(0).entries.get(0);
        FilterMacroEditorModel.Section second = definition.getSections().get(1);
        definition.moveEntryToSection(gaussian, second, 1);

        FilterMacroEditorModel.MacroDefinition reparsed = FilterMacroEditorModel.parse(definition.render());
        assertEquals(1, reparsed.getSections().size());
        List<FilterMacroEditorModel.Entry> entries = reparsed.getSections().get(0).entries;
        assertEquals("Median", entries.get(0).label);
        assertEquals("Gaussian Blur", entries.get(1).label);
    }

    @Test
    public void mutationAfterParse_rerendersConsistently() {
        String macro =
                "// === STANDARD CLEANUP ===\n" +
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n" +
                "run(\"Median...\", \"radius=2 stack\");";

        FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(macro);
        FilterMacroEditorModel.Section section = definition.getSections().get(0);
        definition.addAssignmentEntry(section, "threshold", "2000");

        FilterMacroEditorModel.MacroDefinition reparsed = FilterMacroEditorModel.parse(definition.render());
        List<FilterMacroEditorModel.Entry> entries = reparsed.getSections().get(0).entries;
        assertEquals(3, entries.size());
        assertEquals("Threshold", entries.get(2).label);
        assertEquals("threshold", entries.get(2).parameters.get(0).key);
        assertEquals("2000", entries.get(2).parameters.get(0).getValue());
    }

    @Test
    public void parameterEditWithoutStructuralMutation_preservesAllBytesExceptChangedValue() throws IOException {
        File preset = new File("src/main/resources/named-filters/defaultFilter.ijm");
        String macro = readUtf8(preset);
        FilterMacroEditorModel.MacroDefinition definition = FilterMacroEditorModel.parse(macro);

        definition.getSections().get(0).entries.get(0).parameters.get(0).setValue("4");

        String expected = macro.replaceFirst("sigma=2", "sigma=4");
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),
                definition.render().getBytes(StandardCharsets.UTF_8));
    }

    private static File[] bundledPresetFiles() {
        File directory = new File("src/main/resources/named-filters");
        File[] files = directory.listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    private static String readUtf8(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
