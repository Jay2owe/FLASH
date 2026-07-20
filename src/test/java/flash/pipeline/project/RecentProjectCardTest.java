package flash.pipeline.project;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.intelligence.AnalysisStatus;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
        statuses.put(Integer.valueOf(FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE), AnalysisStatus.DONE);
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

    @Test
    public void statusUnavailableDoesNotBlockLaterOpenResolve() {
        RecentProject recent = new RecentProject("Project", "C:/projects/p/project.json", 1L);
        RecentProjectCard card = new RecentProjectCard(recent, false, 1L, null);

        card.applyStatusResult(null);

        assertEquals("status unavailable", card.progressTextForTests());
        assertFalse(card.isUnresolved());
        assertNull(card.resolvedProjectJson());
    }

    @Test
    public void unresolvedStatusShowsUnavailableTextAndLocateAction() {
        final AtomicInteger locateCalls = new AtomicInteger();
        RecentProject recent = new RecentProject("Project", "C:/missing/project.json", 1L);
        RecentProjectCard card = new RecentProjectCard(recent, false, 1L,
                new NoOpActions() {
                    @Override public void locate(RecentProjectCard card) {
                        locateCalls.incrementAndGet();
                    }
                });

        card.applyStatusResult(RecentProjectCard.StatusResult.unresolved(
                "Unavailable - still syncing or offline?"));

        assertTrue(card.isUnresolved());
        assertEquals("Unavailable - still syncing or offline?", card.progressTextForTests());
        assertTrue(card.locateButtonVisibleForTests());

        card.clickLocateForTests();

        assertEquals(1, locateCalls.get());
    }

    @Test
    public void resolvedStatusHidesLocateAndKeepsRelocationOutcome() {
        RecentProject recent = new RecentProject("Project", "C:/old/project.json", 1L);
        RecentProjectCard card = new RecentProjectCard(recent, false, 1L, null);
        ProjectService.ResolveOutcome outcome = new ProjectService.ResolveOutcome(
                new File("C:/new/project.json"), "C:/old/project.json", true);

        card.applyStatusResult(RecentProjectCard.StatusResult.resolved(outcome,
                "finished Intensity - next: Spatial"));

        assertFalse(card.isUnresolved());
        assertFalse(card.locateButtonVisibleForTests());
        assertSame(outcome, card.resolveOutcome());
        assertEquals(outcome.projectJson, card.resolvedProjectJson());
    }

    private static class NoOpActions implements RecentProjectCard.Actions {
        @Override public void open(RecentProjectCard card) {
        }

        @Override public void edit(RecentProjectCard card) {
        }

        @Override public void locate(RecentProjectCard card) {
        }

        @Override public void remove(RecentProjectCard card) {
        }
    }
}
