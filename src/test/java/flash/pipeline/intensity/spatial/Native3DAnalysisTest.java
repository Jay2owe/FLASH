package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.IntensityAnalysisV2;
import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.io.CsvTableIO;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Native3DAnalysisTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void twoDimensionalPlanDoesNotSelectNative3dOutput() throws Exception {
        IntensitySpatialConfig config = nativeConfig(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D);

        Object plan = outputPlan(temp.newFolder("two-dimensional"),
                new String[]{"DAPI"}, config, 1);

        assertTrue(selectedKeys(plan).contains(IntensitySpatialOutputKey.base("DAPI")));
        assertFalse(selectedKeys(plan).contains(IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.NATIVE_3D)));
    }

    @Test
    public void shallowStackSuppressesNative3dAndStillSelectsBaseOutput() throws Exception {
        IntensitySpatialConfig config = nativeConfig(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D);

        Object plan = outputPlan(temp.newFolder("shallow"),
                new String[]{"DAPI"}, config, 4);

        assertTrue(selectedKeys(plan).contains(IntensitySpatialOutputKey.base("DAPI")));
        assertFalse(selectedKeys(plan).contains(IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.NATIVE_3D)));

        String log = captureImageJLogOutput(new ThrowingRunnable() {
            @Override
            public void run() {
                IntensitySpatialResult result = IntensitySpatialRunner.standard().measure(
                        new IntensitySpatialContext(config, stripeStack(18, 18, 4, 0.0),
                                null, 1, null, IntensitySpatialOutputMode.NATIVE_3D,
                                "shallow", "DAPI", "", null));
                assertTrue(result.isEmpty());
            }
        });
        assertTrue(log.contains("native 3D skipped"));
    }

    @Test
    public void true3dStackWritesNativeColumnsTo3dCsv() throws Exception {
        IntensitySpatialConfig config = nativeConfig(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D);

        IntensitySpatialResult result = IntensitySpatialRunner.standard().measure(
                new IntensitySpatialContext(config, stripeStack(32, 32, 6, 0.0),
                        null, 1, null, IntensitySpatialOutputMode.NATIVE_3D,
                        "deep", "DAPI", "", null));

        assertTrue(result.values().containsKey(Anisotropy3DAnalysis.COLUMN_COHERENCY));
        assertTrue(result.value(Anisotropy3DAnalysis.COLUMN_COHERENCY) > 0.8);

        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        result.writeTo(table, 0);
        File out = IntensitySpatialOutputKey.of("DAPI",
                IntensitySpatialOutputMode.NATIVE_3D).csvFile(temp.newFolder("csv"));
        CsvTableIO.writeResultsTableCsv(out, table, Arrays.asList(
                "Region", "Hemisphere", "ROI", "Animal Name",
                Anisotropy3DAnalysis.COLUMN_COHERENCY,
                Anisotropy3DAnalysis.COLUMN_ANGLE,
                Anisotropy3DAnalysis.COLUMN_ENTROPY));

        assertTrue(out.isFile());
        String csv = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("Region,Hemisphere,ROI,Animal Name,Intensity_Anisotropy3DCoherency"));
    }

    @Test
    public void distanceShell3dReportsPositiveSlopeAwayFromPartnerMask() {
        ShellFixture fixture = shellFixture(25, 25, 7);
        IntensitySpatialResult result = new DistanceShell3DAnalysis().measure(new IntensitySpatialPairContext(
                distanceShellConfig(), fixture.source, null, null,
                fixture.partner, null, fixture.partnerMask, 1, null,
                IntensitySpatialOutputMode.NATIVE_3D, "shell", "DAPI", "mCherry", "", null));

        assertTrue(result.value("DAPI_DistShell3DSlope_mCherry") > 0.0);
        assertTrue(result.value("DAPI_DistShell3D0to2_mCherry")
                < result.value("DAPI_DistShell3D4to6_mCherry"));
    }

    @Test
    public void crossmark3dColumnsUsePairNamingAndBinarizedOnlyAtEnd() {
        List<String> columns = new CrossMark3DAnalysis().columns(
                nativeConfig(IntensitySpatialConfig.AnalysisKey.CROSSMARK_3D),
                "DAPI", "mCherry", true, true);

        assertTrue(columns.contains("DAPI_Pearson3D_mCherry"));
        assertTrue(columns.contains("DAPI_CostesP3D_mCherry_binarized"));
        assertTrue(columns.contains("DAPI_MandersM13D_mCherry_binarized"));
        for (String column : columns) {
            if (column.contains("_binarized")) {
                assertTrue(column.endsWith("_binarized"));
                assertFalse(column.contains("_binarized_"));
            }
        }
    }

    private static IntensitySpatialConfig nativeConfig(IntensitySpatialConfig.AnalysisKey key) {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .native3dEnabled(true)
                .addAnalysis(key)
                .permutations(5)
                .build();
    }

    private static IntensitySpatialConfig distanceShellConfig() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .native3dEnabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D)
                .shellWidthUm(2.0)
                .shellCount(4)
                .build();
    }

    private static Object outputPlan(File root,
                                     String[] channels,
                                     IntensitySpatialConfig config,
                                     int stackDepth) throws Exception {
        Method method = IntensityAnalysisV2.class.getDeclaredMethod("buildOutputPlan",
                File.class, String[].class, boolean.class, int.class,
                IntensitySpatialConfig.class, int.class, boolean.class);
        method.setAccessible(true);
        return method.invoke(null, root, channels, Boolean.FALSE, Integer.valueOf(-1),
                config, Integer.valueOf(stackDepth), Boolean.FALSE);
    }

    @SuppressWarnings("unchecked")
    private static List<IntensitySpatialOutputKey> selectedKeys(Object plan) throws Exception {
        Method method = plan.getClass().getDeclaredMethod("selectedKeys");
        method.setAccessible(true);
        return (List<IntensitySpatialOutputKey>) method.invoke(plan);
    }

    private static ImagePlus stripeStack(int width, int height, int depth, double orientationDegrees) {
        ImageStack stack = new ImageStack(width, height);
        double orientation = Math.toRadians(orientationDegrees);
        double normal = Math.PI / 2.0 - orientation;
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        for (int z = 0; z < depth; z++) {
            float[] pixels = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double coordinate = (x - cx) * Math.cos(normal) + (y - cy) * Math.sin(normal);
                    pixels[y * width + x] = (float) (100.0
                            + 70.0 * Math.sin(2.0 * Math.PI * coordinate / 8.0)
                            + z * 0.25);
                }
            }
            stack.addSlice(new FloatProcessor(width, height, pixels, null));
        }
        return image("stripe", stack);
    }

    private static ShellFixture shellFixture(int width, int height, int depth) {
        ImageStack source = new ImageStack(width, height);
        ImageStack partner = new ImageStack(width, height);
        ImageStack mask = new ImageStack(width, height);
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        double cz = (depth - 1) / 2.0;
        for (int z = 0; z < depth; z++) {
            float[] sourcePixels = new float[width * height];
            float[] partnerPixels = new float[width * height];
            float[] maskPixels = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double dx = x - cx;
                    double dy = y - cy;
                    double dz = z - cz;
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    int index = y * width + x;
                    sourcePixels[index] = (float) (5.0 + distance);
                    if (distance <= 2.0) {
                        partnerPixels[index] = 255.0f;
                        maskPixels[index] = 255.0f;
                    }
                }
            }
            source.addSlice(new FloatProcessor(width, height, sourcePixels, null));
            partner.addSlice(new FloatProcessor(width, height, partnerPixels, null));
            mask.addSlice(new FloatProcessor(width, height, maskPixels, null));
        }
        return new ShellFixture(image("source", source), image("partner", partner), image("mask", mask));
    }

    private static ImagePlus image(String title, ImageStack stack) {
        ImagePlus image = new ImagePlus(title, stack);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.pixelDepth = 1.0;
        calibration.setUnit("um");
        image.setCalibration(calibration);
        return image;
    }

    private static String captureImageJLogOutput(ThrowingRunnable action) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, "UTF-8"));
        String ijLog = null;
        try {
            if (IJ.getLog() != null) IJ.log("\\Clear");
            action.run();
            ijLog = IJ.getLog();
        } finally {
            System.out.flush();
            System.setOut(originalOut);
        }
        return out.toString("UTF-8") + (ijLog == null ? "" : ijLog);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class ShellFixture {
        final ImagePlus source;
        final ImagePlus partner;
        final ImagePlus partnerMask;

        private ShellFixture(ImagePlus source, ImagePlus partner, ImagePlus partnerMask) {
            this.source = source;
            this.partner = partner;
            this.partnerMask = partnerMask;
        }
    }
}
