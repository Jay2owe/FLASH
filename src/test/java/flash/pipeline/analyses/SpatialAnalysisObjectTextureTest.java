package flash.pipeline.analyses;

import flash.pipeline.analyses.wizard.SpatialSetupConfig;
import flash.pipeline.io.CalibrationIO;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.morphometry.ObjectPatch;
import flash.pipeline.morphometry.ObjectPatchBuilder;
import flash.pipeline.morphometry.ObjectTextureGLCM;
import flash.pipeline.results.ObjectCsvColumnOrder;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import mcib3d.image3d.ImageHandler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpatialAnalysisObjectTextureTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void executeWritesObjectTextureColumnsAndReusesExistingOutputsHeadlessly() throws Exception {
        File root = temp.newFolder("spatial-object-texture");
        Fixture fixture = createFixture(root);

        runTexture(root);

        File channelFile = new File(fixture.objectsDir, "A.csv");
        CsvTableIO.ChannelData first = CsvTableIO.loadChannelCsv(channelFile, "A");
        assertNotNull(first);
        assertTextureColumnsPresent(first);
        assertFinite(first, 0, "MorphTexture_GLCMContrast");
        assertFinite(first, 0, "MorphTexture_FractalDim");
        assertFinite(first, 0, "MorphTexture_ClassDistance");
        assertFinite(first, 0, "MorphTexture_F1");

        double expectedSliceAverage = averageSliceGlcmContrast(fixture.labelImage, fixture.rawImage, 1);
        double mipContrast = ObjectTextureGLCM.compute(ObjectPatchBuilder.buildMIP(
                fixture.objectByLabel.get(Integer.valueOf(1)), fixture.labelImage, fixture.rawImage)).contrast;
        assertEquals(expectedSliceAverage, first.getDouble(0, "MorphTexture_GLCMContrast"), 1e-6);
        assertNotEquals(mipContrast, first.getDouble(0, "MorphTexture_GLCMContrast"), 1e-6);

        File centroidFile = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).configurationWriteDir(),
                "morph_texture_centroids_A.txt");
        assertTrue(centroidFile.isFile());
        long centroidModified = centroidFile.lastModified();
        String firstDistance = first.get(0, "MorphTexture_ClassDistance");

        runTexture(root);

        CsvTableIO.ChannelData second = CsvTableIO.loadChannelCsv(channelFile, "A");
        assertNotNull(second);
        assertEquals(firstDistance, second.get(0, "MorphTexture_ClassDistance"));
        assertEquals(centroidModified, centroidFile.lastModified());

        blankColumn(channelFile, "A", "MorphTexture_ClassDistance");
        runTexture(root);
        assertEquals(centroidModified, centroidFile.lastModified());
        CsvTableIO.ChannelData loadedCentroids = CsvTableIO.loadChannelCsv(channelFile, "A");
        assertNotNull(loadedCentroids);
        assertFinite(loadedCentroids, 0, "MorphTexture_ClassDistance");

        blankColumn(channelFile, "A", "MorphTexture_ClassDistance");
        assertTrue(centroidFile.delete());
        runTexture(root);
        assertTrue(centroidFile.isFile());
    }

    @Test
    public void executeWritesNative3DTextureColumnsHeadlessly() throws Exception {
        File root = temp.newFolder("spatial-object-texture-3d");
        Fixture fixture = createFixture(root);

        runNative3DTexture(root);

        File channelFile = new File(fixture.objectsDir, "A.csv");
        CsvTableIO.ChannelData data = CsvTableIO.loadChannelCsv(channelFile, "A");
        assertNotNull(data);
        assertNative3DTextureColumnsPresent(data);
        assertFinite(data, 0, "MorphTexture_GLCM3DContrast");
        assertFinite(data, 0, "MorphTexture_Class3DDistance");
        assertFinite(data, 0, "MorphTexture_F3D8");

        File centroidFile = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).configurationWriteDir(),
                "morph_texture_centroids_3D_A.txt");
        assertTrue(centroidFile.isFile());
    }

    @Test
    public void executeWritesComplexArborizationColumnsAndShollProfilesHeadlessly() throws Exception {
        File root = temp.newFolder("spatial-object-arborization");
        Fixture fixture = createFixture(root);

        runComplexMorphometry(root);

        File channelFile = new File(fixture.objectsDir, "A.csv");
        CsvTableIO.ChannelData data = CsvTableIO.loadChannelCsv(channelFile, "A");
        assertNotNull(data);
        assertArborizationColumnsPresent(data);
        assertAnyFinite(data, "Morph_ShollCriticalRadius_um");
        assertAnyFinite(data, "Morph_ShollCriticalIntersections");
        assertAnyFinite(data, "Morph_ShollSchoenenIndex");
        assertFinite(data, 0, "Morph_SkeletonBranches");
        assertFinite(data, 0, "Morph_SkeletonJunctions");
        assertFinite(data, 0, "Morph_SkeletonEndpoints");

        File shollFile = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .tablesMorphometryWriteDir(), "A_ShollProfile.csv");
        assertTrue(shollFile.isFile());
        assertTrue(Files.readAllLines(shollFile.toPath()).size() > 1);
    }

    @Test
    public void oldComplexOutputsMissingArborizationColumnsAreRecomputed() throws Exception {
        File root = temp.newFolder("spatial-old-complex-arborization");
        Fixture fixture = createFixture(root);

        runComplexMorphometry(root);
        File channelFile = new File(fixture.objectsDir, "A.csv");
        File shollFile = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .tablesMorphometryWriteDir(), "A_ShollProfile.csv");
        removeColumns(channelFile, "A", arborizationColumns());
        assertTrue(shollFile.delete());

        runComplexMorphometry(root);

        CsvTableIO.ChannelData data = CsvTableIO.loadChannelCsv(channelFile, "A");
        assertNotNull(data);
        assertArborizationColumnsPresent(data);
        assertAnyFinite(data, "Morph_ShollSchoenenIndex");
        assertTrue(shollFile.isFile());

        assertTrue(shollFile.delete());
        runComplexMorphometry(root);
        assertTrue(shollFile.isFile());
    }

    @Test
    public void objectCsvColumnOrderPlacesTextureAfterMorphGroups() {
        List<String> ordered = ObjectCsvColumnOrder.orderedColumns("A", Arrays.asList(
                "MorphTexture_F1",
                "MorphTexture_F3D1",
                "Morph_Sphericity",
                "Morph_Area_um2",
                "Mean",
                "MorphTexture_GLCMContrast",
                "MorphTexture_GLCM3DContrast",
                "MorphTexture_FractalDim"
        ), Arrays.asList("A"));

        assertEquals(Arrays.asList(
                "Mean",
                "Morph_Area_um2",
                "Morph_Sphericity",
                "MorphTexture_GLCMContrast",
                "MorphTexture_GLCM3DContrast",
                "MorphTexture_FractalDim",
                "MorphTexture_F1",
                "MorphTexture_F3D1"
        ), ordered);
    }

    @Test
    public void objectDataAvailabilityDetectsTextureColumnFamilies() {
        List<String> header = Arrays.asList(
                "MorphTexture_GLCMContrast",
                "MorphTexture_GLCMASM",
                "MorphTexture_GLCMCorrelation",
                "MorphTexture_GLCMEntropy",
                "MorphTexture_GLCMHomogeneity",
                "MorphTexture_FractalDim",
                "MorphTexture_FractalDim_R2",
                "MorphTexture_LacunarityMean",
                "MorphTexture_LacunaritySpread",
                "MorphTexture_ClassLabel",
                "MorphTexture_ClassDistance",
                "MorphTexture_F1",
                "MorphTexture_F2",
                "MorphTexture_F3",
                "MorphTexture_F4",
                "MorphTexture_F5",
                "MorphTexture_F6",
                "MorphTexture_F7",
                "MorphTexture_F8",
                "MorphTexture_GLCM3DContrast",
                "MorphTexture_GLCM3DASM",
                "MorphTexture_GLCM3DCorrelation",
                "MorphTexture_GLCM3DEntropy",
                "MorphTexture_GLCM3DHomogeneity",
                "MorphTexture_Class3DLabel",
                "MorphTexture_Class3DDistance",
                "MorphTexture_F3D1",
                "MorphTexture_F3D2",
                "MorphTexture_F3D3",
                "MorphTexture_F3D4",
                "MorphTexture_F3D5",
                "MorphTexture_F3D6",
                "MorphTexture_F3D7",
                "MorphTexture_F3D8");
        Map<String, Integer> colIdx = new HashMap<String, Integer>();
        for (int i = 0; i < header.size(); i++) {
            colIdx.put(header.get(i), Integer.valueOf(i));
        }
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(new ArrayList<String>(Arrays.asList(
                "1", "1", "1", "1", "1",
                "1", "1", "1", "1",
                "0", "1", "1", "1", "1", "1", "1", "1", "1", "1",
                "1", "1", "1", "1", "1",
                "0", "1", "1", "1", "1", "1", "1", "1", "1", "1")));
        Map<String, CsvTableIO.ChannelData> channels =
                new LinkedHashMap<String, CsvTableIO.ChannelData>();
        channels.put("A", new CsvTableIO.ChannelData("A",
                new ArrayList<String>(header), rows, colIdx));

        SpatialAnalysis.SpatialObjectDataAvailability detected =
                SpatialAnalysis.SpatialObjectDataAvailability.detect(channels, Arrays.asList("A"));

        assertTrue(detected.hasObjectGLCMForAllChannels(Arrays.asList("A")));
        assertTrue(detected.hasObjectFractalForAllChannels(Arrays.asList("A")));
        assertTrue(detected.hasObjectTextureClassForAllChannels(Arrays.asList("A")));
        assertTrue(detected.hasObjectGLCM3DForAllChannels(Arrays.asList("A")));
        assertTrue(detected.hasObjectTextureClass3DForAllChannels(Arrays.asList("A")));
    }

    private static double averageSliceGlcmContrast(ImagePlus label, ImagePlus raw, int labelValue) {
        Object3DInt object = objectByLabel(label).get(Integer.valueOf(labelValue));
        double sum = 0.0;
        int count = 0;
        for (int z = ObjectPatchBuilder.zMin(object, label); z <= ObjectPatchBuilder.zMax(object, label); z++) {
            ObjectPatch patch = ObjectPatchBuilder.buildSlice(object, label, raw, z);
            if (patch.objectPixelCount() == 0) continue;
            ObjectTextureGLCM.Result result = ObjectTextureGLCM.compute(patch);
            if (!result.valid) continue;
            sum += result.contrast;
            count++;
        }
        return sum / count;
    }

    private static void assertTextureColumnsPresent(CsvTableIO.ChannelData data) {
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCMContrast"));
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCMASM"));
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCMCorrelation"));
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCMEntropy"));
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCMHomogeneity"));
        assertTrue(data.colIdx.containsKey("MorphTexture_FractalDim"));
        assertTrue(data.colIdx.containsKey("MorphTexture_FractalDim_R2"));
        assertTrue(data.colIdx.containsKey("MorphTexture_LacunarityMean"));
        assertTrue(data.colIdx.containsKey("MorphTexture_LacunaritySpread"));
        assertTrue(data.colIdx.containsKey("MorphTexture_ClassLabel"));
        assertTrue(data.colIdx.containsKey("MorphTexture_ClassDistance"));
        assertTrue(data.colIdx.containsKey("MorphTexture_F8"));
    }

    private static void assertNative3DTextureColumnsPresent(CsvTableIO.ChannelData data) {
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCM3DContrast"));
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCM3DASM"));
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCM3DCorrelation"));
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCM3DEntropy"));
        assertTrue(data.colIdx.containsKey("MorphTexture_GLCM3DHomogeneity"));
        assertTrue(data.colIdx.containsKey("MorphTexture_Class3DLabel"));
        assertTrue(data.colIdx.containsKey("MorphTexture_Class3DDistance"));
        assertTrue(data.colIdx.containsKey("MorphTexture_F3D8"));
    }

    private static void assertArborizationColumnsPresent(CsvTableIO.ChannelData data) {
        assertTrue(data.colIdx.containsKey("Morph_ShollCriticalRadius_um"));
        assertTrue(data.colIdx.containsKey("Morph_ShollCriticalIntersections"));
        assertTrue(data.colIdx.containsKey("Morph_ShollSchoenenIndex"));
        assertTrue(data.colIdx.containsKey("Morph_ShollPrimaryBranches"));
        assertTrue(data.colIdx.containsKey("Morph_SkeletonBranches"));
        assertTrue(data.colIdx.containsKey("Morph_SkeletonJunctions"));
        assertTrue(data.colIdx.containsKey("Morph_SkeletonEndpoints"));
    }

    private static void assertFinite(CsvTableIO.ChannelData data, int row, String column) {
        double value = data.getDouble(row, column);
        assertTrue(column + " should be finite", !Double.isNaN(value) && !Double.isInfinite(value));
    }

    private static void assertAnyFinite(CsvTableIO.ChannelData data, String column) {
        for (int row = 0; row < data.rows.size(); row++) {
            double value = data.getDouble(row, column);
            if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                return;
            }
        }
        assertTrue(column + " should have at least one finite value", false);
    }

    private static void blankColumn(File channelFile, String channelName, String column) {
        CsvTableIO.ChannelData data = CsvTableIO.loadChannelCsv(channelFile, channelName);
        assertNotNull(data);
        for (int row = 0; row < data.rows.size(); row++) {
            data.set(row, column, "");
        }
        CsvTableIO.writeChannelCsv(channelFile, data);
    }

    private static List<String> arborizationColumns() {
        return Arrays.asList("Morph_ShollCriticalRadius_um",
                "Morph_ShollCriticalIntersections",
                "Morph_ShollSchoenenIndex",
                "Morph_ShollPrimaryBranches",
                "Morph_SkeletonBranches",
                "Morph_SkeletonJunctions",
                "Morph_SkeletonEndpoints");
    }

    private static void removeColumns(File channelFile, String channelName, List<String> columns) {
        CsvTableIO.ChannelData data = CsvTableIO.loadChannelCsv(channelFile, channelName);
        assertNotNull(data);
        List<String> header = new ArrayList<String>();
        for (String column : data.header) {
            if (!columns.contains(column)) {
                header.add(column);
            }
        }
        Map<String, Integer> colIdx = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < header.size(); i++) {
            colIdx.put(header.get(i), Integer.valueOf(i));
        }
        List<List<String>> rows = new ArrayList<List<String>>();
        for (int row = 0; row < data.rows.size(); row++) {
            List<String> newRow = new ArrayList<String>();
            for (String column : header) {
                newRow.add(data.get(row, column));
            }
            rows.add(newRow);
        }
        CsvTableIO.writeChannelCsv(channelFile,
                new CsvTableIO.ChannelData(channelName, header, rows, colIdx));
    }

    private static void runTexture(File root) {
        SpatialSetupConfig.DerivedConfig config = new SpatialSetupConfig.DerivedConfig();
        config.doObjectGLCM = true;
        config.doObjectFractal = true;
        config.doObjectTextureClass = true;
        config.textureClassK = 2;
        SpatialAnalysis analysis = new SpatialAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setWizardConfig(config);
        analysis.execute(root.getAbsolutePath());
    }

    private static void runNative3DTexture(File root) {
        SpatialSetupConfig.DerivedConfig config = new SpatialSetupConfig.DerivedConfig();
        config.doNative3DTexture = true;
        config.textureClassK = 2;
        SpatialAnalysis analysis = new SpatialAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setWizardConfig(config);
        analysis.execute(root.getAbsolutePath());
    }

    private static void runComplexMorphometry(File root) {
        SpatialSetupConfig.DerivedConfig config = new SpatialSetupConfig.DerivedConfig();
        config.do3DMorphology = true;
        config.doCompositeIndices = true;
        SpatialAnalysis analysis = new SpatialAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setWizardConfig(config);
        analysis.execute(root.getAbsolutePath());
    }

    private static Fixture createFixture(File root) throws Exception {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        File objectsDir = layout.tablesObjectsWriteDir();
        assertTrue(objectsDir.mkdirs());
        CalibrationIO.write(objectsDir, 1.0, 1.0, 1.0, "um");

        File animalDir = new File(layout.analysisImagesObjectsMasksDir(), "Mouse1");
        assertTrue(animalDir.mkdirs());
        File inputDir = new File(root, "input");
        assertTrue(inputDir.mkdirs());

        ImagePlus label = labelImage();
        ImagePlus raw = rawImage(label);
        assertTrue(new FileSaver(label).saveAsTiffStack(
                new File(animalDir, "A_objects_LH_SCN.tif").getAbsolutePath()));
        assertTrue(new FileSaver(raw).saveAsTiffStack(
                new File(inputDir, "series1.tif").getAbsolutePath()));

        writeChannel(objectsDir);
        return new Fixture(objectsDir, label, raw, objectByLabel(label));
    }

    private static void writeChannel(File objectsDir) throws Exception {
        PrintWriter out = new PrintWriter(new File(objectsDir, "A.csv"), "UTF-8");
        try {
            out.println("SCN,Animal Name,Hemisphere,Region,ROI,Label,XM,YM,ZM");
            out.println("1,Mouse1,LH,SCN,,1,11,11,2");
            out.println("1,Mouse1,LH,SCN,,2,33,14,3");
            out.println("1,Mouse1,LH,SCN,,3,18,43,1");
        } finally {
            out.close();
        }
    }

    private static ImagePlus labelImage() {
        int width = 64;
        int height = 64;
        int slices = 8;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ShortProcessor sp = new ShortProcessor(width, height);
            fillObject(sp, z, 1, 5, 18, 5, 18, 1, 3);
            fillObject(sp, z, 2, 25, 40, 6, 21, 2, 4);
            fillObject(sp, z, 3, 10, 25, 35, 50, 0, 2);
            stack.addSlice(sp);
        }
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus rawImage(ImagePlus label) {
        int width = label.getWidth();
        int height = label.getHeight();
        int slices = label.getStack().getSize();
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            FloatProcessor fp = new FloatProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int labelValue = (int) label.getStack().getProcessor(z + 1).getf(x, y);
                    fp.setf(x, y, intensity(labelValue, x, y, z));
                }
            }
            stack.addSlice(fp);
        }
        return new ImagePlus("raw", stack);
    }

    private static float intensity(int label, int x, int y, int z) {
        if (label == 1) {
            return ((x + z) % 4 < 2) ? 20.0f : 180.0f;
        }
        if (label == 2) {
            return ((y + z) % 4 < 2) ? 40.0f : 210.0f;
        }
        if (label == 3) {
            return (((x + y + z) % 6) < 3) ? 70.0f : 230.0f;
        }
        return 0.0f;
    }

    private static void fillObject(ShortProcessor sp,
                                   int z,
                                   int label,
                                   int xMin,
                                   int xMax,
                                   int yMin,
                                   int yMax,
                                   int zMin,
                                   int zMax) {
        if (z < zMin || z > zMax) return;
        for (int y = yMin; y <= yMax; y++) {
            for (int x = xMin; x <= xMax; x++) {
                sp.set(x, y, label);
            }
        }
    }

    private static Map<Integer, Object3DInt> objectByLabel(ImagePlus label) {
        Objects3DIntPopulation population = new Objects3DIntPopulation(ImageHandler.wrap(label));
        Map<Integer, Object3DInt> out = new LinkedHashMap<Integer, Object3DInt>();
        for (Object3DInt object : population.getObjects3DInt()) {
            out.put(Integer.valueOf((int) object.getLabel()), object);
        }
        return out;
    }

    private static final class Fixture {
        final File objectsDir;
        final ImagePlus labelImage;
        final ImagePlus rawImage;
        final Map<Integer, Object3DInt> objectByLabel;

        private Fixture(File objectsDir,
                        ImagePlus labelImage,
                        ImagePlus rawImage,
                        Map<Integer, Object3DInt> objectByLabel) {
            this.objectsDir = objectsDir;
            this.labelImage = labelImage;
            this.rawImage = rawImage;
            this.objectByLabel = objectByLabel;
        }
    }
}
