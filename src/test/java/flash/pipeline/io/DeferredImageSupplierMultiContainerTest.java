package flash.pipeline.io;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises the multi-container routing math without touching Bio-Formats.
 * Uses {@code DeferredImageSupplier.multiContainerForTests} to inject
 * synthetic per-container series counts.
 */
public class DeferredImageSupplierMultiContainerTest {

    private static final File A = new File("A.lif");
    private static final File B = new File("B.lif");
    private static final File C = new File("C.lif");

    @Test
    public void allSeriesIncluded_globalIndexFlattensAcrossContainers() {
        DeferredImageSupplier s = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A, B),
                new int[]{3, 2},
                null);

        assertEquals(5, s.getTotalSeries());
        // First 3 global indices live in A, next 2 in B.
        assertSame(A, s.getContainerFileForSeries(0));
        assertSame(A, s.getContainerFileForSeries(2));
        assertSame(B, s.getContainerFileForSeries(3));
        assertSame(B, s.getContainerFileForSeries(4));
    }

    @Test
    public void singleContainer_emptyIncludeBehavesAsAllSeries() {
        DeferredImageSupplier s = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A, B),
                new int[]{4, 1},
                Arrays.<List<Integer>>asList(
                        Collections.<Integer>emptyList(),
                        Collections.<Integer>emptyList()));

        assertEquals(5, s.getTotalSeries());
    }

    @Test
    public void perContainerNarrowing_routesToExplicitLocalIndices() {
        // A: include series 0 and 2 only. B: include series 1 only.
        DeferredImageSupplier s = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A, B),
                new int[]{4, 3},
                Arrays.<List<Integer>>asList(
                        Arrays.asList(Integer.valueOf(0), Integer.valueOf(2)),
                        Arrays.asList(Integer.valueOf(1))));

        assertEquals(3, s.getTotalSeries());
        assertSame(A, s.getContainerFileForSeries(0));
        assertSame(A, s.getContainerFileForSeries(1));
        assertSame(B, s.getContainerFileForSeries(2));
        assertEquals(0, s.getLocalSeriesIndexForSeries(0));
        assertEquals(2, s.getLocalSeriesIndexForSeries(1));
        assertEquals(1, s.getLocalSeriesIndexForSeries(2));
    }

    @Test
    public void outOfRangeIncludeIndexesThrowClearly() {
        try {
            DeferredImageSupplier.multiContainerForTests(
                    Arrays.asList(A),
                    new int[]{3},
                    Arrays.<List<Integer>>asList(
                            Arrays.asList(Integer.valueOf(0), Integer.valueOf(99), Integer.valueOf(2))));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("series index 99"));
            assertTrue(e.getMessage().contains("total series 3"));
        }
    }

    @Test
    public void allContainersReportZeroSeries_throws() {
        try {
            DeferredImageSupplier.multiContainerForTests(
                    Arrays.asList(A),
                    new int[]{0},
                    null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("no series remain"));
        }
    }

    @Test
    public void zeroSeriesContainerBeforeValidContainer_routesValidContainer() {
        DeferredImageSupplier s = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A, B),
                new int[]{0, 2},
                Arrays.<List<Integer>>asList(
                        Collections.<Integer>emptyList(),
                        Collections.<Integer>emptyList()));

        assertEquals(2, s.getTotalSeries());
        assertSame(B, s.getContainerFileForSeries(0));
        assertSame(B, s.getContainerFileForSeries(1));
    }

    @Test
    public void emptyContainerList_throws() {
        try {
            DeferredImageSupplier.multiContainerForTests(
                    Collections.<File>emptyList(),
                    new int[]{},
                    null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("containers required"));
        }
    }

    @Test
    public void containerCountMismatchedToSeriesCounts_throws() {
        try {
            DeferredImageSupplier.multiContainerForTests(
                    Arrays.asList(A, B),
                    new int[]{3},   // only one count for two containers
                    null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("perContainerSeriesCounts length"));
        }
    }

    @Test
    public void getContainerFiles_returnsImmutableSnapshot() {
        DeferredImageSupplier s = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A, B, C),
                new int[]{1, 1, 1},
                null);

        List<File> files = s.getContainerFiles();
        assertEquals(3, files.size());
        assertSame(A, files.get(0));
        try {
            files.add(new File("X.lif"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void containerFile_returnsFirstForBackwardCompat() {
        DeferredImageSupplier s = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A, B),
                new int[]{2, 1},
                null);

        assertSame(A, s.getContainerFile());
    }

    @Test
    public void getContainerFileForSeries_outOfRange_throws() {
        DeferredImageSupplier s = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A),
                new int[]{2},
                null);

        try {
            s.getContainerFileForSeries(2);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("out of range"));
        }
    }

    @Test
    public void modeIsContainer() {
        DeferredImageSupplier s = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A, B),
                new int[]{1, 1},
                null);

        assertEquals(DeferredImageSupplier.Mode.CONTAINER, s.getMode());
        assertNotNull(s.getContainerFiles());
    }

    @Test
    public void containerDisplayName_summarisesMultiContainer() {
        DeferredImageSupplier single = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A), new int[]{1}, null);
        assertEquals("A.lif", single.getContainerDisplayName());

        DeferredImageSupplier multi = DeferredImageSupplier.multiContainerForTests(
                Arrays.asList(A, B, C), new int[]{1, 1, 1}, null);
        // First container's name plus a count of the rest.
        assertTrue(multi.getContainerDisplayName().startsWith("A.lif"));
        assertTrue(multi.getContainerDisplayName().contains("2 more"));
        assertNotSame(single.getContainerDisplayName(), multi.getContainerDisplayName());
    }
}
