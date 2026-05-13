package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VariationExecutorTest {

    @Test
    public void noopStrategyPublishesFiveResultsOnEdtAndCompletes() throws Exception {
        final CountDownLatch delivered = new CountDownLatch(5);
        final List<Integer> objectCounts = Collections.synchronizedList(new ArrayList<Integer>());
        final AtomicBoolean allCallbacksOnEdt = new AtomicBoolean(true);

        VariationExecutor worker = new VariationExecutor(singleCellSweep(),
                new NoopStrategy(5),
                null,
                (result, index) -> {
                    allCallbacksOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread());
                    objectCounts.add(Integer.valueOf(result.getNObjects()));
                    delivered.countDown();
                },
                null);

        worker.execute();

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        worker.get(5, TimeUnit.SECONDS);
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });

        assertEquals(5, objectCounts.size());
        assertEquals(Integer.valueOf(0), objectCounts.get(0));
        assertEquals(Integer.valueOf(4), objectCounts.get(4));
        assertTrue(allCallbacksOnEdt.get());
    }

    @Test
    public void cancellationIsHonouredBetweenPublishes() throws Exception {
        final AtomicInteger published = new AtomicInteger();
        final AtomicInteger delivered = new AtomicInteger();
        final CountDownLatch firstDelivered = new CountDownLatch(1);
        final AtomicReference<VariationExecutor> workerRef =
                new AtomicReference<VariationExecutor>();

        VariationStrategy cancellable = new VariationStrategy() {
            @Override
            public void dispatch(ParameterSweep sweep,
                                 Consumer<VariationResult> publisher,
                                 BooleanSupplier cancelCheck) throws Exception {
                for (int i = 0; i < 5; i++) {
                    if (cancelCheck.getAsBoolean()) {
                        return;
                    }
                    published.incrementAndGet();
                    publisher.accept(fakeResult(i));
                    if (i == 0) {
                        while (!cancelCheck.getAsBoolean()) {
                            Thread.sleep(5L);
                        }
                    }
                }
            }
        };

        VariationExecutor worker = new VariationExecutor(singleCellSweep(),
                cancellable,
                null,
                (result, index) -> {
                    if (delivered.incrementAndGet() == 1) {
                        firstDelivered.countDown();
                        workerRef.get().cancel(false);
                    }
                },
                null);
        workerRef.set(worker);

        worker.execute();

        assertTrue(firstDelivered.await(5, TimeUnit.SECONDS));
        try {
            worker.get(5, TimeUnit.SECONDS);
            fail("Expected worker.get() to report cancellation.");
        } catch (CancellationException expected) {
            // Expected once the EDT callback cancels the SwingWorker.
        }
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });

        assertEquals(1, published.get());
        assertEquals(1, delivered.get());
    }

    private static ParameterSweep singleCellSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc");
    }

    private static VariationResult fakeResult(int index) {
        ImagePlus label = new ImagePlus("fake-" + index, new ByteProcessor(1, 1));
        return VariationResult.success(ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(index))
                .build(), label, index, 0L, null);
    }
}
