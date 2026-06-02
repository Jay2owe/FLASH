package flash.pipeline.report;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class StudyYamlWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void qualityReportWritesStudyYamlAlongsideHtml() throws Exception {
        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory(tmp.getRoot().getAbsolutePath());
        report.setGlobalSettings(false, true, 4, false, "Auto-Overwrite");

        report.addGenericAnalysis("Test Analysis", 1200);

        File html = new File(tmp.getRoot(), "FLASH/Results/QC/QC_Report.html");
        File yaml = new File(tmp.getRoot(), "FLASH/Results/QC/study.yaml");
        assertTrue(html.isFile());
        assertTrue(yaml.isFile());

        String text = read(yaml);
        assertTrue(text.contains("study:"));
        assertTrue(text.contains("biosample:"));
        assertTrue(text.contains("specimen:"));
        assertTrue(text.contains("acquisition:"));
        assertTrue(text.contains("image:"));
        assertTrue(text.contains("analysis:"));
        assertTrue(text.contains("file:"));
        assertTrue(text.contains("protocol:"));
        assertTrue(text.contains("name: \"Test Analysis\""));
    }

    @Test
    public void projectFileMetadataAndFilenameParserPopulateModules() throws Exception {
        File projectRoot = tmp.newFolder("rembi-project");
        File raw = new File(projectRoot, "Experiment-Mouse7_LH_CA1_Control.tif");
        assertTrue(raw.createNewFile());

        ProjectFile project = new ProjectFile();
        project.name = "Hippocampus Study";
        project.outputRoot = projectRoot.getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = raw.getAbsolutePath();
        item.animalId = "Mouse7";
        item.hemisphere = "LH";
        item.region = "CA1";
        item.condition = "Control";
        item.notes = "coronal section";
        project.items.add(item);

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        File settings = layout.configurationWriteDir();
        assertTrue(settings.isDirectory() || settings.mkdirs());
        ProjectFileIO.write(settings, project);

        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory(projectRoot.getAbsolutePath());
        report.setGlobalSettings(false, false, 1, true, "Skip Existing");
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("Threshold", "Otsu");
        report.addSection("3D Object Analysis", params);
        report.write3DObjectQC();

        String text = read(layout.qcStudyMetadataWriteFile());
        assertTrue(text.contains("title: \"Hippocampus Study\""));
        assertTrue(text.contains("biosample_id: \"Mouse7\""));
        assertTrue(text.contains("hemisphere: \"LH\""));
        assertTrue(text.contains("anatomical_region: \"CA1\""));
        assertTrue(text.contains("condition: \"Control\""));
        assertTrue(text.contains("notes: \"coronal section\""));
        assertTrue(text.contains("source_file: \"" + raw.getAbsolutePath().replace("\\", "\\\\") + "\""));
        assertTrue(text.contains("\"Threshold\": \"Otsu\""));
    }

    @Test
    public void yamlEscapesQuotesBackslashesAndNewlines() throws Exception {
        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory(tmp.getRoot().getAbsolutePath());
        report.setGlobalSettings(false, false, 1, false, "Auto-Overwrite");
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("Key \"quoted\": path", "Line 1\nLine 2 at C:\\data\\image.tif");
        report.addSection("Escaping", params);
        report.write3DObjectQC();

        String text = read(new File(tmp.getRoot(), "FLASH/Results/QC/study.yaml"));

        assertTrue(text.contains("\"Key \\\"quoted\\\": path\": \"Line 1\\nLine 2 at C:\\\\data\\\\image.tif\""));
    }

    @Test
    public void projectContainerMetadataPopulatesDimensionsWithoutLosingProjectFields() throws Exception {
        File projectRoot = tmp.newFolder("ome-project");
        File control = new File(projectRoot, "Experiment-MouseA_LH_CA1_Control.ome.tif");
        File treated = new File(projectRoot, "Experiment-MouseB_RH_CA3_Treated.ome.tif");
        writeTinyTiff(control, 3, 4);
        writeTinyTiff(treated, 5, 6);

        ProjectFile project = new ProjectFile();
        project.name = "Two OME TIFFs";
        project.outputRoot = projectRoot.getAbsolutePath();
        project.items.add(item(control, "MouseA", "LH", "CA1", "Control"));
        project.items.add(item(treated, "MouseB", "RH", "CA3", "Treated"));

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        File settings = layout.configurationWriteDir();
        assertTrue(settings.isDirectory() || settings.mkdirs());
        ProjectFileIO.write(settings, project);

        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory(projectRoot.getAbsolutePath());
        report.setGlobalSettings(false, false, 1, false, "Auto-Overwrite");
        report.addGenericAnalysis("Metadata Test", 1);

        String text = read(layout.qcStudyMetadataWriteFile());
        assertTrue(text.contains("metadata_sources:"));
        assertTrue(text.contains("\"Bio-Formats SeriesMeta\""));
        assertTrue(text.contains("animal_id: \"MouseA\""));
        assertTrue(text.contains("animal_id: \"MouseB\""));
        assertTrue(text.contains("condition: \"Control\""));
        assertTrue(text.contains("condition: \"Treated\""));
        assertTrue(text.contains("x_pixels: 3"));
        assertTrue(text.contains("y_pixels: 4"));
        assertTrue(text.contains("x_pixels: 5"));
        assertTrue(text.contains("y_pixels: 6"));
    }

    @Test
    public void bareTiffProjectItemsWithEmptySeriesParseSourceFilenamesWhenFieldsBlank() throws Exception {
        File projectRoot = tmp.newFolder("bare-tiff-project");
        File control = new File(projectRoot, "Experiment-MouseA_LH_CA1_Control.tif");
        File treated = new File(projectRoot, "Experiment-MouseB_RH_CA3_Treated.tif");
        writeTinyTiff(control, 3, 4);
        writeTinyTiff(treated, 5, 6);

        ProjectFile project = new ProjectFile();
        project.name = "Bare TIFFs";
        project.outputRoot = projectRoot.getAbsolutePath();
        project.items.add(item(control, "", "", "", ""));
        project.items.add(item(treated, "", "", "", ""));

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        File settings = layout.configurationWriteDir();
        assertTrue(settings.isDirectory() || settings.mkdirs());
        ProjectFileIO.write(settings, project);

        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory(projectRoot.getAbsolutePath());
        report.setGlobalSettings(false, false, 1, false, "Auto-Overwrite");
        report.addGenericAnalysis("Metadata Test", 1);

        String text = read(layout.qcStudyMetadataWriteFile());
        assertTrue(text.contains("animal_id: \"MouseA\""));
        assertTrue(text.contains("animal_id: \"MouseB\""));
        assertTrue(text.contains("hemisphere: \"LH\""));
        assertTrue(text.contains("hemisphere: \"RH\""));
        assertTrue(text.contains("region: \"CA1\""));
        assertTrue(text.contains("region: \"CA3\""));
        assertTrue(text.contains("condition: \"Control\""));
        assertTrue(text.contains("condition: \"Treated\""));
        assertFalse(text.contains("animal_id: \"Experiment-MouseA\""));
        assertFalse(text.contains("animal_id: \"Experiment-MouseB\""));
        assertTrue(text.contains("x_pixels: 3"));
        assertTrue(text.contains("x_pixels: 5"));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static ProjectFile.Item item(File source, String animal, String hemisphere,
                                         String region, String condition) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = source.getAbsolutePath();
        item.animalId = animal;
        item.hemisphere = hemisphere;
        item.region = region;
        item.condition = condition;
        return item;
    }

    private static void writeTinyTiff(File file, int width, int height) {
        ByteProcessor processor = new ByteProcessor(width, height);
        processor.set(1, 1, 255);
        ImagePlus image = new ImagePlus("tiny", processor);
        IJ.saveAsTiff(image, file.getAbsolutePath());
        image.close();
    }
}
