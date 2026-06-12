package flash.pipeline.progress;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnalysisProgressReporterTest {

    @Test
    public void completedProgressIsMonotonicAndSnapshotsContainStatus() {
        TestSink sink = new TestSink();
        TestRecorder recorder = new TestRecorder();
        TestClock clock = new TestClock();
        AnalysisProgressReporter reporter = AnalysisProgressReporter.createForTest(
                "3D Object Analysis", 3, sink, recorder, clock, 0L, Long.MAX_VALUE);

        reporter.setPhase("object detection");
        AnalysisProgressReporter.WorkHandle a = reporter.begin("image 1", "loading");
        clock.advance(1000L);
        reporter.update(a, "DAPI");
        reporter.complete(a, "image 1 done");

        AnalysisProgressReporter.WorkHandle b = reporter.begin("image 2");
        clock.advance(1000L);
        reporter.fail(b, "image 2 failed");

        Map<String, Object> snapshot = reporter.snapshot();
        assertEquals(2, ((Number) snapshot.get("doneUnits")).intValue());
        assertEquals(1, ((Number) snapshot.get("completedUnits")).intValue());
        assertEquals(1, ((Number) snapshot.get("failedUnits")).intValue());
        assertTrue(String.valueOf(snapshot.get("status")).contains("2/3 done"));
        assertTrue(sink.statuses.get(sink.statuses.size() - 1).contains("2/3 done"));
        assertTrue(recorder.snapshots.size() > 0);
    }

    @Test
    public void concurrentWorkersLeaveNoActiveTasksAndDoNotDoubleCount() throws Exception {
        final TestSink sink = new TestSink();
        final TestRecorder recorder = new TestRecorder();
        final TestClock clock = new TestClock();
        final AnalysisProgressReporter reporter = AnalysisProgressReporter.createForTest(
                "Spatial Analysis", 20, sink, recorder, clock, 0L, Long.MAX_VALUE);
        final CountDownLatch ready = new CountDownLatch(10);
        final CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            futures.add(pool.submit(new Runnable() {
                @Override public void run() {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    AnalysisProgressReporter.WorkHandle handle =
                            reporter.begin("section " + idx, "CPC");
                    reporter.update(handle, "done");
                    reporter.complete(handle, "section " + idx);
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        Map<String, Object> snapshot = reporter.snapshot();
        assertEquals(10, ((Number) snapshot.get("doneUnits")).intValue());
        assertEquals(10, ((Number) snapshot.get("completedUnits")).intValue());
        assertEquals(0, ((Number) snapshot.get("activeUnits")).intValue());
    }

    @Test
    public void addTotalUnitsAllowsLatePhaseDiscovery() {
        TestSink sink = new TestSink();
        TestRecorder recorder = new TestRecorder();
        TestClock clock = new TestClock();
        AnalysisProgressReporter reporter = AnalysisProgressReporter.createForTest(
                "Spatial Analysis", 0, sink, recorder, clock, 0L, Long.MAX_VALUE);

        reporter.setPhase("CPC");
        reporter.addTotalUnits(2);
        AnalysisProgressReporter.WorkHandle first = reporter.begin("pair A/B");
        reporter.complete(first, "pair A/B");

        Map<String, Object> snapshot = reporter.snapshot();
        assertEquals(2, ((Number) snapshot.get("totalUnits")).intValue());
        assertEquals(1, ((Number) snapshot.get("doneUnits")).intValue());
        assertTrue(String.valueOf(snapshot.get("status")).contains("50%"));
    }

    private static final class TestSink implements AnalysisProgressReporter.Sink {
        final List<String> logs = Collections.synchronizedList(new ArrayList<String>());
        final List<String> statuses = Collections.synchronizedList(new ArrayList<String>());
        final List<String> progresses = Collections.synchronizedList(new ArrayList<String>());

        @Override public void log(String message) {
            logs.add(message);
        }

        @Override public void status(String message) {
            statuses.add(message);
        }

        @Override public void progress(int done, int total) {
            progresses.add(done + "/" + total);
        }
    }

    private static final class TestRecorder implements AnalysisProgressReporter.Recorder {
        final List<Map<String, Object>> snapshots =
                Collections.synchronizedList(new ArrayList<Map<String, Object>>());

        @Override public void recordProgressSnapshot(Map<String, Object> snapshot) {
            snapshots.add(snapshot);
        }
    }

    private static final class TestClock implements AnalysisProgressReporter.Clock {
        private long now;

        @Override public long nowMillis() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }
}
