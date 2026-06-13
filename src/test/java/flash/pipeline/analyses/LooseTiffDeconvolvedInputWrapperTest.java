package flash.pipeline.analyses;

import flash.pipeline.deconv.DeconvolutionIO;
import flash.pipeline.io.DeferredImageSupplier;
import ij.IJ;
import ij.ImagePlus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LooseTiffDeconvolvedInputWrapperTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void splitMergeWrapperUsesFreshDeconvolvedMirrorForLooseTiffs() throws Exception {
        assertWrapperUsesFreshMirror(new SplitAndMergeImageChannelsAnalysis(),
                SplitAndMergeImageChannelsAnalysis.class, "split-merge-loose");
    }

    @Test
    public void intensityWrapperUsesFreshDeconvolvedMirrorForLooseTiffs() throws Exception {
        assertWrapperUsesFreshMirror(new IntensityAnalysisV2(),
                IntensityAnalysisV2.class, "intensity-loose");
    }

    @Test
    public void threeDObjectWrapperUsesFreshDeconvolvedMirrorForLooseTiffs() throws Exception {
        assertWrapperUsesFreshMirror(new ThreeDObjectAnalysis(),
                ThreeDObjectAnalysis.class, "object-loose");
    }

    private void assertWrapperUsesFreshMirror(Object analysis,
                                              Class<?> analysisClass,
                                              String folderName) throws Exception {
        File root = temp.newFolder(folderName);
        String sourceLabel = root.getName();
        File raw = new File(root, "MouseA_LH_SCN.tif");
        writeSyntheticTiff(raw, "raw", 3, 2, 1);

        File mirror = DeconvolutionIO.mergedDeconvFile(root, "MouseA_LH_SCN");
        writeSyntheticTiff(mirror, "deconvolved", 7, 5, 1);
        assertTrue(raw.setLastModified(1000L));
        assertTrue(mirror.setLastModified(2000L));

        DeferredImageSupplier rawSupplier = new DeferredImageSupplier(
                Collections.singletonList(raw), sourceLabel);
        setField(analysis, "useDeconvolvedInput", Boolean.TRUE);

        DeferredImageSupplier wrapped = invokeWrapInputSupplier(
                analysis, analysisClass, root.getAbsolutePath(), rawSupplier);
        ImagePlus opened = wrapped.openSeriesMaterialized(0);
        try {
            assertNotNull(opened);
            assertEquals(sourceLabel + " - MouseA_LH_SCN", opened.getTitle());
            assertEquals(7, opened.getWidth());
            assertEquals(5, opened.getHeight());
        } finally {
            close(opened);
            rawSupplier.shutdownPrefetch();
            wrapped.shutdownPrefetch();
        }
    }

    private static DeferredImageSupplier invokeWrapInputSupplier(
            Object analysis,
            Class<?> analysisClass,
            String directory,
            DeferredImageSupplier supplier) throws Exception {
        Method method = analysisClass.getDeclaredMethod(
                "wrapInputSupplier", String.class, DeferredImageSupplier.class);
        method.setAccessible(true);
        return (DeferredImageSupplier) method.invoke(analysis, directory, supplier);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void writeSyntheticTiff(File target, String title,
                                           int width, int height, int slices) {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            assertTrue(parent.mkdirs());
        }
        ImagePlus image = IJ.createImage(title, "8-bit ramp", width, height, slices);
        try {
            IJ.saveAsTiff(image, target.getAbsolutePath());
        } finally {
            close(image);
        }
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
