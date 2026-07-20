package flash.pipeline.segmentation;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.action.LabelImgExporter;
import flash.pipeline.cellpose.Cellpose3DRunner;
import flash.pipeline.stardist.StarDist3DRunner;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Independent label-domain oracles for the StarDist and Cellpose boundaries. */
public class SegmentationLabelIdentityTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void realTrackMateExportUsesTheSamePositiveIdsAsStatisticsAndFiltering() throws Exception {
        ImagePlus source = IJ.createHyperStack("source", 18, 8, 1, 1, 2, 8);
        Model model = new Model();
        Spot low0 = spot(3.0, 3.0, 0, 0.10);
        Spot low1 = spot(3.0, 3.0, 1, 0.20);
        Spot high0 = spot(13.0, 3.0, 0, 0.90);
        Spot high1 = spot(13.0, 3.0, 1, 0.80);

        model.beginUpdate();
        try {
            model.addSpotTo(low0, Integer.valueOf(0));
            model.addSpotTo(low1, Integer.valueOf(1));
            model.addSpotTo(high0, Integer.valueOf(0));
            model.addSpotTo(high1, Integer.valueOf(1));
            model.addEdge(low0, low1, 1.0);
            model.addEdge(high0, high1, 1.0);
        } finally {
            model.endUpdate();
        }

        Integer lowTrack = model.getTrackModel().trackIDOf(low0);
        Integer highTrack = model.getTrackModel().trackIDOf(high0);
        assertNotNull(lowTrack);
        assertNotNull(highTrack);
        assertFalse(lowTrack.equals(highTrack));

        // Independent TrackMate 7.14 oracle: LABEL_IS_TRACK_ID reserves zero
        // for background and paints track ID n as pixel n + 1.
        int lowLabel = lowTrack.intValue() + 1;
        int highLabel = highTrack.intValue() + 1;
        Set<Integer> expected = setOf(lowLabel, highLabel);

        ImagePlus labels = LabelImgExporter.createLabelImagePlus(
                model, source, false, true,
                LabelImgExporter.LabelIdPainting.LABEL_IS_TRACK_ID);
        assertNotNull(labels);
        ResultsTable stats = buildStarDistObjectStats(model);
        prepareStarDistLabels(labels);
        validateStarDistJoin(labels, stats);

        assertEquals(expected, labelsIn(labels));
        assertEquals(expected, labelsIn(stats));

        int removed = StarDist3DRunner.applyObjectFilters(
                labels, stats, 0.0, Double.POSITIVE_INFINITY, 0.5, 0.0);

        assertEquals(1, removed);
        assertEquals(setOf(highLabel), labelsIn(labels));
        labels.changes = false;
        labels.close();
        source.changes = false;
        source.close();
    }

    @Test
    public void labelsAcrossUnsignedShortBoundaryRemainExactInBothJavaRunners() throws Exception {
        ImagePlus oracle = labelImage(0, 65_535, 65_536, 70_000);
        prepareStarDistLabels(oracle);

        assertEquals(32, oracle.getBitDepth());
        assertEquals(setOf(65_535, 65_536, 70_000), labelsIn(oracle));

        Path maskPath = temp.newFile("wide_cp_masks.tif").toPath();
        assertTrue(IJ.saveAsTiff(oracle, maskPath.toString()));
        ImagePlus reference = IJ.createImage("reference", "8-bit black", 4, 1, 1);
        ImagePlus loaded = readCellposeMask(maskPath, reference);

        assertNotNull(loaded);
        assertEquals(32, loaded.getBitDepth());
        assertEquals(setOf(65_535, 65_536, 70_000), labelsIn(loaded));
        assertEquals(3, Cellpose3DRunner.countLabels(loaded));
        loaded.changes = false;
        loaded.close();
        oracle.changes = false;
        oracle.close();
        reference.changes = false;
        reference.close();
    }

    @Test
    public void invalidLabelDomainFailsBeforeAnyNarrowingOrInstallation() throws Exception {
        ImagePlus fractional = labelImage(1.0f, 2.5f);
        try {
            prepareStarDistLabels(fractional);
            fail("Expected fractional label rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("finite, integral, non-negative"));
        }
        assertEquals(32, fractional.getBitDepth());
        assertEquals(2.5f, fractional.getProcessor().getf(1), 0.0f);

        ImagePlus nonFinite = labelImage(1.0f, Float.NaN);
        Path maskPath = temp.newFile("invalid_cp_masks.tif").toPath();
        assertTrue(IJ.saveAsTiff(nonFinite, maskPath.toString()));
        ImagePlus reference = IJ.createImage("reference", "8-bit black", 2, 1, 1);
        assertNull(readCellposeMask(maskPath, reference));

        fractional.changes = false;
        fractional.close();
        nonFinite.changes = false;
        nonFinite.close();
        reference.changes = false;
        reference.close();
    }

    @Test
    public void pythonWorkerDeclaresCheckedUint32OutputInsteadOfUint16Wrapping() throws Exception {
        String worker = resourceText("/flash/pipeline/cellpose/cellpose_loop.py");

        assertTrue(worker.contains("dtype = np.uint16 if maximum <= 65535 else np.uint32"));
        assertTrue(worker.contains("maximum > 16777216"));
        assertTrue(worker.contains("np.array_equal"));
        assertTrue(worker.contains("tifffile.imwrite(str(path), canonical)"));
        assertFalse(worker.contains("except Exception:\n        pass\n    io.imsave"));
    }

    private static Spot spot(double x, double y, int frame, double quality) {
        Spot spot = new Spot(x, y, 0.0, 1.25, quality);
        spot.putFeature(Spot.FRAME, Double.valueOf(frame));
        spot.putFeature(Spot.POSITION_T, Double.valueOf(frame));
        spot.putFeature("MEAN_INTENSITY_CH1", Double.valueOf(100.0 + quality));
        return spot;
    }

    private static ImagePlus readCellposeMask(Path path, ImagePlus reference) throws Exception {
        Method method = Cellpose3DRunner.class.getDeclaredMethod(
                "readMaskImage", Path.class, ImagePlus.class, String.class);
        method.setAccessible(true);
        return (ImagePlus) method.invoke(null, path, reference, "identity-test");
    }

    private static ResultsTable buildStarDistObjectStats(Model model) throws Exception {
        return (ResultsTable) invokeStarDist("buildObjectStats",
                new Class<?>[] {Model.class}, new Object[] {model});
    }

    private static void prepareStarDistLabels(ImagePlus labels) throws Exception {
        invokeStarDist("prepareLabelImageForInstall",
                new Class<?>[] {ImagePlus.class, String.class},
                new Object[] {labels, "StarDist test"});
    }

    private static void validateStarDistJoin(ImagePlus labels, ResultsTable table) throws Exception {
        invokeStarDist("validatePixelTableJoin",
                new Class<?>[] {ImagePlus.class, ResultsTable.class},
                new Object[] {labels, table});
    }

    private static Object invokeStarDist(String name, Class<?>[] types, Object[] args)
            throws Exception {
        Method method = StarDist3DRunner.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw e;
        }
    }

    private static ImagePlus labelImage(int... values) {
        float[] floats = new float[values.length];
        for (int i = 0; i < values.length; i++) floats[i] = values[i];
        return labelImage(floats);
    }

    private static ImagePlus labelImage(float... values) {
        FloatProcessor processor = new FloatProcessor(values.length, 1);
        for (int i = 0; i < values.length; i++) processor.setf(i, values[i]);
        ImageStack stack = new ImageStack(values.length, 1);
        stack.addSlice(processor);
        return new ImagePlus("labels", stack);
    }

    private static Set<Integer> labelsIn(ImagePlus image) {
        Set<Integer> labels = new HashSet<Integer>();
        for (int s = 1; s <= image.getStackSize(); s++) {
            ImageProcessor processor = image.getStack().getProcessor(s);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = Math.round(processor.getf(i));
                if (label > 0) labels.add(Integer.valueOf(label));
            }
        }
        return labels;
    }

    private static Set<Integer> labelsIn(ResultsTable table) {
        Set<Integer> labels = new HashSet<Integer>();
        for (int row = 0; row < table.size(); row++) {
            labels.add(Integer.valueOf((int) table.getValue("Label", row)));
        }
        return labels;
    }

    private static Set<Integer> setOf(Integer... values) {
        return new HashSet<Integer>(Arrays.asList(values));
    }

    private static String resourceText(String name) throws Exception {
        InputStream input = SegmentationLabelIdentityTest.class.getResourceAsStream(name);
        assertNotNull(input);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }
}
