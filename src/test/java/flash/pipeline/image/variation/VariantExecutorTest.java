package flash.pipeline.image.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.Combiner;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VariantExecutorTest {

    @After
    public void tearDown() {
        VariantExecutor.setBackendForTests(null);
    }

    @Test
    public void nativePlansRunSuccessfullyAgainstDuplicateSources() {
        ImagePlus source = onePixel(2.0f);
        List<VariantPlan> plans = Collections.singletonList(
                new VariantPlan("add", nativeDag("value=1"), Collections.<String, String>emptyMap()));

        List<VariantResult> results = VariantExecutor.runAll(source, plans, null);

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals(3.0, pixel(results.get(0).output), 0.0);
        assertEquals(2.0, pixel(source), 0.0);
        assertNotSame(source, results.get(0).output);
    }

    @Test
    public void failedVariantReturnsErrorAndDoesNotAbortBatch() {
        List<VariantPlan> plans = Arrays.asList(
                new VariantPlan("good", nativeDag("value=1"),
                        Collections.<String, String>emptyMap()),
                new VariantPlan("bad", nativeDagWithMissingOutput(),
                        Collections.<String, String>emptyMap()),
                new VariantPlan("still good", nativeDag("value=2"),
                        Collections.<String, String>emptyMap()));

        List<VariantResult> results = VariantExecutor.runAll(onePixel(2.0f), plans, null);

        assertEquals(3, results.size());
        assertTrue(results.get(0).isSuccess());
        assertFalse(results.get(1).isSuccess());
        assertTrue(results.get(1).error.getMessage().contains("DAG output"));
        assertTrue(results.get(2).isSuccess());
        assertEquals(4.0, pixel(results.get(2).output), 0.0);
    }

    @Test
    public void nativeWorkerCountCapsAtTwo() {
        assertEquals(1, VariantExecutor.nativeWorkerCount(0));
        assertEquals(1, VariantExecutor.nativeWorkerCount(1));
        assertTrue(VariantExecutor.nativeWorkerCount(5) <= 2);
    }

    @Test
    public void nativePlansUseNoMoreThanTwoConcurrentWorkers() {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final AtomicInteger nativeCalls = new AtomicInteger();
        VariantExecutor.setBackendForTests(new VariantExecutor.ExecutorBackend() {
            @Override
            public ImagePlus runNative(ImagePlus source, DagIR dag) throws Exception {
                nativeCalls.incrementAndGet();
                int now = active.incrementAndGet();
                updateMax(maxActive, now);
                try {
                    Thread.sleep(50L);
                    return source.duplicate();
                } finally {
                    active.decrementAndGet();
                }
            }

            @Override
            public ImagePlus runLegacy(ImagePlus source, DagIR dag) {
                throw new AssertionError("legacy backend should not be called");
            }
        });

        List<VariantPlan> plans = new ArrayList<VariantPlan>();
        for (int i = 0; i < 5; i++) {
            plans.add(new VariantPlan("n" + i, nativeDag("value=" + i),
                    Collections.<String, String>emptyMap()));
        }

        List<VariantResult> results = VariantExecutor.runAll(onePixel(2.0f), plans, null);

        assertEquals(5, results.size());
        assertEquals(5, nativeCalls.get());
        assertTrue("max active was " + maxActive.get(), maxActive.get() <= 2);
    }

    @Test
    public void anyLegacyPlanForcesSerialPolicyWithCorrectBackend() {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final AtomicInteger nativeCalls = new AtomicInteger();
        final AtomicInteger legacyCalls = new AtomicInteger();
        VariantExecutor.setBackendForTests(new VariantExecutor.ExecutorBackend() {
            @Override
            public ImagePlus runNative(ImagePlus source, DagIR dag) throws Exception {
                nativeCalls.incrementAndGet();
                int now = active.incrementAndGet();
                updateMax(maxActive, now);
                try {
                    Thread.sleep(20L);
                    return source.duplicate();
                } finally {
                    active.decrementAndGet();
                }
            }

            @Override
            public ImagePlus runLegacy(ImagePlus source, DagIR dag) throws Exception {
                legacyCalls.incrementAndGet();
                int now = active.incrementAndGet();
                updateMax(maxActive, now);
                try {
                    Thread.sleep(20L);
                    return source.duplicate();
                } finally {
                    active.decrementAndGet();
                }
            }
        });

        List<VariantPlan> plans = Arrays.asList(
                new VariantPlan("legacy", legacyDag(), Collections.<String, String>emptyMap()),
                new VariantPlan("native", nativeDag("value=1"), Collections.<String, String>emptyMap()),
                new VariantPlan("native 2", nativeDag("value=2"), Collections.<String, String>emptyMap()));

        List<VariantResult> results = VariantExecutor.runAll(onePixel(2.0f), plans, null);

        assertEquals(3, results.size());
        assertEquals(1, legacyCalls.get());
        assertEquals(2, nativeCalls.get());
        assertEquals(1, maxActive.get());
    }

    @Test
    public void legacyPlanUsesLegacyBackendNotNativeBackend() {
        final AtomicInteger nativeCalls = new AtomicInteger();
        final AtomicInteger legacyCalls = new AtomicInteger();
        VariantExecutor.setBackendForTests(new VariantExecutor.ExecutorBackend() {
            @Override
            public ImagePlus runNative(ImagePlus source, DagIR dag) {
                nativeCalls.incrementAndGet();
                throw new AssertionError("legacy plan called native backend");
            }

            @Override
            public ImagePlus runLegacy(ImagePlus source, DagIR dag) {
                legacyCalls.incrementAndGet();
                return source.duplicate();
            }
        });

        List<VariantResult> results = VariantExecutor.runAll(onePixel(2.0f),
                Collections.singletonList(new VariantPlan("legacy", legacyDag(),
                        Collections.<String, String>emptyMap())),
                null);

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals(0, nativeCalls.get());
        assertEquals(1, legacyCalls.get());
        assertNull(results.get(0).error);
    }

    private static DagIR nativeDag(String args) {
        return new DagIR(1,
                Collections.singletonList(new DagLine("A",
                        Collections.singletonList(new DagNode("add", OpType.ADD, args)))),
                Collections.<Combiner>emptyList(),
                "A",
                "native");
    }

    private static DagIR nativeDagWithMissingOutput() {
        return new DagIR(1,
                Collections.singletonList(new DagLine("A",
                        Collections.singletonList(new DagNode("add", OpType.ADD, "value=1")))),
                Collections.<Combiner>emptyList(),
                "missing",
                "native");
    }

    private static DagIR legacyDag() {
        return new DagIR(1,
                Collections.singletonList(new DagLine("A",
                        Collections.singletonList(new DagNode("legacy", OpType.UNKNOWN,
                                "", "Invert", "Process > Binary > Invert")))),
                Collections.<Combiner>emptyList(),
                "A",
                "legacy");
    }

    private static ImagePlus onePixel(float value) {
        FloatProcessor fp = new FloatProcessor(1, 1);
        fp.setf(0, 0, value);
        ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(fp);
        return new ImagePlus("source", stack);
    }

    private static double pixel(ImagePlus imp) {
        return imp.getStack().getProcessor(1).getf(0, 0);
    }

    private static void updateMax(AtomicInteger maxActive, int candidate) {
        while (true) {
            int current = maxActive.get();
            if (candidate <= current) return;
            if (maxActive.compareAndSet(current, candidate)) return;
        }
    }
}
