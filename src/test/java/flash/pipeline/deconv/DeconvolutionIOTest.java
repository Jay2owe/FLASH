package flash.pipeline.deconv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DeconvolutionIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void paramsHashIsStableAcrossMapOrdering() {
        Map<String, String> first = new LinkedHashMap<String, String>();
        first.put("engine", "DL2");
        first.put("algorithm", "RL_TV");
        first.put("iterations", "15");
        first.put("sampleRi", "1.450000");

        Map<String, String> reordered = new LinkedHashMap<String, String>();
        reordered.put("sampleRi", "1.450000");
        reordered.put("iterations", "15");
        reordered.put("algorithm", "RL_TV");
        reordered.put("engine", "DL2");

        assertEquals(DeconvolutionIO.paramsHash(first), DeconvolutionIO.paramsHash(reordered));
    }

    @Test
    public void paramsHashChangesWhenAnyRelevantParameterChanges() {
        Map<String, String> base = new LinkedHashMap<String, String>();
        base.put("engine", "DL2");
        base.put("algorithm", "RL_TV");
        base.put("iterations", "15");
        base.put("sampleRi", "1.450000");

        Map<String, String> changed = new LinkedHashMap<String, String>(base);
        changed.put("iterations", "16");

        assertNotEquals(DeconvolutionIO.paramsHash(base), DeconvolutionIO.paramsHash(changed));
    }

    @Test
    public void cacheFreshDependsOnSourceAndCacheModificationTimes() throws Exception {
        File source = temp.newFile("source.lif");
        File cache = temp.newFile("cached.tif");
        Files.write(source.toPath(), "src".getBytes(StandardCharsets.UTF_8));
        Files.write(cache.toPath(), "cache".getBytes(StandardCharsets.UTF_8));

        assertTrue(source.setLastModified(1_000L));
        assertTrue(cache.setLastModified(2_000L));
        assertTrue(DeconvolutionIO.isCacheFresh(source, cache));

        assertTrue(cache.setLastModified(500L));
        assertFalse(DeconvolutionIO.isCacheFresh(source, cache));
    }

    @Test
    public void mergedDeconvolvedOutputUsesCanonicalFileName() {
        File root = temp.getRoot();
        File merged = DeconvolutionIO.mergedDeconvFile(root, "My Image");
        assertEquals(new File(new File(new File(root, "FLASH"), "3D Deconvolution"), "My Image_deconv.tif"), merged);
    }

    @Test
    public void deconvolutionOutputsUseFlashAnalysisFolderWithLegacyReadFallback() {
        File root = temp.getRoot();

        assertEquals(new File(new File(root, "FLASH"), "3D Deconvolution"),
                DeconvolutionIO.deconvOutDir(root));

        List<File> candidates = DeconvolutionIO.mergedDeconvFileReadCandidates(root, "My Image");
        assertEquals(3, candidates.size());
        assertEquals(new File(new File(new File(root, "FLASH"), "3D Deconvolution"), "My Image_deconv.tif"),
                candidates.get(0));
        assertEquals(new File(new File(new File(root, "FLASH"), "02 - 3D Deconvolution"), "My Image_deconv.tif"),
                candidates.get(1));
        assertEquals(new File(new File(new File(root, "Image Analysis"), "Deconvolved"), "My Image_deconv.tif"),
                candidates.get(2));
    }

    @Test
    public void cacheOutputsUseFlashCacheFolderWithLegacyReadFallback() {
        File root = temp.getRoot();
        String paramsHash = "ABC123";

        assertEquals(new File(new File(new File(root, "FLASH"), "Cache"), "3D Deconvolution"),
                DeconvolutionIO.cacheDir(root));
        assertEquals(new File(new File(new File(new File(root, "FLASH"), "Cache"), "3D Deconvolution"), paramsHash),
                DeconvolutionIO.cacheParamsDir(root, paramsHash));

        List<File> candidates = DeconvolutionIO.cacheFileReadCandidates(root, paramsHash, "My Image", 1);
        assertEquals(2, candidates.size());
        assertEquals(new File(new File(new File(new File(new File(root, "FLASH"), "Cache"), "3D Deconvolution"),
                        paramsHash), "My Image_C1.tif"),
                candidates.get(0));
        assertEquals(new File(new File(new File(root, ".deconv_cache"), paramsHash), "My Image_C1.tif"),
                candidates.get(1));
    }
}
