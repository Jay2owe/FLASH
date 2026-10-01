package flash.pipeline.ui.main;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.roi.RoiIO;
import flash.pipeline.runtime.DependencyId;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What each main-dialog analysis writes and which optional runtimes it can
 * use. The side panel reads this; add an entry here when adding an analysis.
 */
public final class AnalysisFacts {

    private static final Map<Integer, List<DependencyId>> USES = new java.util.HashMap<Integer, List<DependencyId>>();

    static {
        USES.put(FLASH_Pipeline.IDX_CREATE_BIN, Arrays.asList(
                DependencyId.BIO_FORMATS_RUNTIME, DependencyId.OBJECTS_COUNTER_3D,
                DependencyId.OBJECTS_COUNTER_3D_PLUS, DependencyId.MCIB3D_CORE,
                DependencyId.STARDIST_RUNTIME, DependencyId.TENSORFLOW_NATIVE_RUNTIME,
                DependencyId.CELLPOSE_RUNTIME));
        USES.put(FLASH_Pipeline.IDX_DRAW_ROIS, Collections.singletonList(DependencyId.BIO_FORMATS_RUNTIME));
        USES.put(FLASH_Pipeline.IDX_DECONVOLUTION, Arrays.asList(
                DependencyId.BIO_FORMATS_RUNTIME, DependencyId.EPFL_PSF_GENERATOR_RUNTIME,
                DependencyId.DECONV_CLIJ2_RUNTIME, DependencyId.DECONVOLUTIONLAB2_RUNTIME,
                DependencyId.ITERATIVE_DECONVOLVE_3D_RUNTIME));
        USES.put(FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION, Collections.<DependencyId>emptyList());
        USES.put(FLASH_Pipeline.IDX_SPLIT_MERGE, Collections.singletonList(DependencyId.BIO_FORMATS_RUNTIME));
        USES.put(FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE, Collections.singletonList(DependencyId.BIO_FORMATS_RUNTIME));
        USES.put(FLASH_Pipeline.IDX_INTENSITY, Arrays.asList(
                DependencyId.BIO_FORMATS_RUNTIME, DependencyId.COLOC2_RUNTIME,
                DependencyId.IMGLIB2_ALGORITHM_RUNTIME, DependencyId.IMGLIB2_FFT_RUNTIME,
                DependencyId.JTRANSFORMS_RUNTIME, DependencyId.ORIENTATIONJ_RUNTIME));
        USES.put(FLASH_Pipeline.IDX_3D_OBJECT, Arrays.asList(
                DependencyId.BIO_FORMATS_RUNTIME, DependencyId.OBJECTS_COUNTER_3D,
                DependencyId.OBJECTS_COUNTER_3D_PLUS, DependencyId.MCIB3D_CORE,
                DependencyId.STARDIST_RUNTIME, DependencyId.TENSORFLOW_NATIVE_RUNTIME,
                DependencyId.CELLPOSE_RUNTIME));
        USES.put(FLASH_Pipeline.IDX_SPATIAL, Arrays.asList(DependencyId.MCIB3D_CORE, DependencyId.JTS_CORE));
        USES.put(FLASH_Pipeline.IDX_LINE_DISTANCE, Collections.<DependencyId>emptyList());
        USES.put(FLASH_Pipeline.IDX_AGGREGATION, Collections.<DependencyId>emptyList());
        USES.put(FLASH_Pipeline.IDX_STATISTICS, Collections.<DependencyId>emptyList());
        USES.put(FLASH_Pipeline.IDX_EXCEL_EXPORT, Collections.singletonList(DependencyId.APACHE_POI_RUNTIME));
    }

    private AnalysisFacts() {}

    /** Optional runtimes the analysis checks before using a feature that needs them. */
    public static List<DependencyId> uses(int analysisIndex) {
        List<DependencyId> ids = USES.get(Integer.valueOf(analysisIndex));
        return ids == null ? Collections.<DependencyId>emptyList() : ids;
    }

    /** True when the analysis has an entry in this table. */
    public static boolean isKnown(int analysisIndex) {
        return USES.containsKey(Integer.valueOf(analysisIndex));
    }

    /** Folder or file the analysis writes into, or null when unknown. */
    public static File output(FlashProjectLayout layout, int analysisIndex) {
        if (layout == null) return null;
        switch (analysisIndex) {
            case FLASH_Pipeline.IDX_CREATE_BIN: return layout.configurationWriteDir();
            case FLASH_Pipeline.IDX_DRAW_ROIS: return RoiIO.roiSetWriteDir(layout.projectRoot());
            case FLASH_Pipeline.IDX_DECONVOLUTION: return layout.analysisImagesDeconvolutionDir();
            case FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION: return layout.tablesSpectralWriteDir();
            case FLASH_Pipeline.IDX_SPLIT_MERGE: return layout.presentationImagesDir();
            case FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE: return layout.representativeFiguresDir();
            case FLASH_Pipeline.IDX_INTENSITY: return layout.tablesIntensityWriteDir();
            case FLASH_Pipeline.IDX_3D_OBJECT: return layout.tablesObjectsWriteDir();
            case FLASH_Pipeline.IDX_SPATIAL: return layout.tablesSpatialWriteDir();
            case FLASH_Pipeline.IDX_LINE_DISTANCE: return layout.tablesLineDistanceWriteDir();
            case FLASH_Pipeline.IDX_AGGREGATION: return layout.tablesProjectSummaryWriteDir();
            case FLASH_Pipeline.IDX_STATISTICS:
                return layout.projectSummaryWriteFile(FlashProjectLayout.STATISTICS_FILENAME);
            case FLASH_Pipeline.IDX_EXCEL_EXPORT: return layout.summaryWorkbookWriteFile();
            default: return null;
        }
    }

    /** {@code output} shown relative to the project folder, with forward slashes. */
    public static String outputForDisplay(String projectDirectory, int analysisIndex) {
        if (projectDirectory == null || projectDirectory.trim().isEmpty()) return "";
        File out = output(FlashProjectLayout.forDirectory(projectDirectory), analysisIndex);
        if (out == null) return "";
        String root = new File(projectDirectory).getAbsolutePath();
        String path = out.getAbsolutePath();
        if (path.startsWith(root)) {
            path = path.substring(root.length());
            while (path.startsWith(File.separator)) path = path.substring(1);
        }
        return path.replace('\\', '/');
    }
}
