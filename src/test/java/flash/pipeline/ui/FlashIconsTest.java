package flash.pipeline.ui;

import org.junit.Test;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class FlashIconsTest {

    @Test
    public void iconCacheIsStableAcrossConcurrentLoads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Icon>> calls = new ArrayList<Callable<Icon>>();
            for (int i = 0; i < 24; i++) {
                calls.add(new Callable<Icon>() {
                    @Override public Icon call() {
                        return FlashIcons.chevronRight(12, FlashTheme.TEXT_HEADER);
                    }
                });
            }

            List<Future<Icon>> futures = pool.invokeAll(calls);
            Icon first = futures.get(0).get();
            assertNotNull(first);
            for (Future<Icon> future : futures) {
                assertSame(first, future.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
