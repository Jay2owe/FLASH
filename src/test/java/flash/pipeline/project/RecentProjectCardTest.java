package flash.pipeline.project;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.intelligence.AnalysisStatus;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class RecentProjectCardTest {

    @Test
    public void relativeLastOpenedFormatsShortDurations() {
        long now = 10L * 24L * 60L * 60L * 1000L;

        assertEquals("now", RecentProjectCard.relativeLastOpened(now - 30L * 1000L, now));
        assertEquals("5m ago", RecentProjectCard.relativeLastOpened(now - 5L * 60L * 1000L, now));
        assertEquals("3h ago", RecentProjectCard.relativeLastOpened(now - 3L * 60L * 60L * 1000L, now));
        assertEquals("3d ago", RecentProjectCard.relativeLastOpened(now - 3L * 24L * 60L * 60L * 1000L, now));
        assertEquals("1w ago", RecentProjectCard.relativeLastOpened(now - 8L * 24L * 60L * 60L * 1000L, now));
    }

    @Test
    public void progressSummaryShowsFirstIncompleteAnalysis() {
        Map<Integer, AnalysisStatus> statuses = new HashMap<Integer, AnalysisStatus>();
        statuses.put(Integer.valueOf(FLASH_Pipeline.IDX_CREATE_BIN), AnalysisStatus.DONE);
        statuses.put(Integer.valueOf(FLASH_Pipeline.IDX_DRAW_ROIS), AnalysisStatus.DONE);
        statuses.put(Integer.valueOf(FLASH_Pipeline.IDX_DECONVOLUTION), AnalysisStatus.DONE);
        statuses.put(Integer.valueOf(FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION), AnalysisStatus.DONE);
        statuses.put(Integer.valueOf(FLASH_Pipeline.IDX_SPLIT_MERGE), AnalysisStatus.DONE);
        statuses.put(Integer.valueOf(FLASH_Pipeline.IDX_INTENSITY), AnalysisStatus.DONE);
        statuses.put(Integer.valueOf(FLASH_Pipeline.IDX_3D_OBJECT), AnalysisStatus.DONE);
        statuses.put(Integer.valueOf(FLASH_Pipeline.IDX_SPATIAL), AnalysisStatus.NOT_STARTED);

        assertEquals("finished 3D Objects - next: Spatial",
                RecentProjectCard.progressSummary(statuses));
    }

    @Test
    public void progressSummaryHandlesEmptyAndCompleteProjects() {
        assertEquals("not started - next: Configuration",
                RecentProjectCard.progressSummary(new HashMap<Integer, AnalysisStatus>()));

        Map<Integer, AnalysisStatus> statuses = new HashMap<Integer, AnalysisStatus>();
        int[] order = FLASH_Pipeline.visibleAnalysisOrderForTests();
        for (int i = 0; i < order.length; i++) {
            statuses.put(Integer.valueOf(order[i]), AnalysisStatus.DONE);
        }
        assertEquals("finished Excel Export - complete",
                RecentProjectCard.progressSummary(statuses));
    }
}
