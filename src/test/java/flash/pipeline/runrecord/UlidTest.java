package flash.pipeline.runrecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UlidTest {

    @Test
    public void correctLength() {
        assertEquals(26, Ulid.next().length());
        assertEquals(26, new Ulid(new Random(1)).generate(1_700_000_000_000L).length());
    }

    @Test
    public void usesCrockfordAlphabetOnly() {
        String ulid = new Ulid(new Random(7)).generate(1_700_000_000_000L);
        for (int i = 0; i < ulid.length(); i++) {
            assertTrue("Unexpected char: " + ulid.charAt(i),
                    "0123456789ABCDEFGHJKMNPQRSTVWXYZ".indexOf(ulid.charAt(i)) >= 0);
        }
    }

    @Test
    public void monotonicWithinSameMillisecond() {
        Ulid ulid = new Ulid(new Random(42));
        String previous = null;
        for (int i = 0; i < 1000; i++) {
            String next = ulid.generate(1_700_000_000_000L);
            if (previous != null) {
                assertTrue("ULID not strictly increasing: " + previous + " -> " + next,
                        next.compareTo(previous) > 0);
            }
            previous = next;
        }
    }

    @Test
    public void twoUlidsSameMillisecondDiffer() {
        Ulid ulid = new Ulid(new Random(99));
        String a = ulid.generate(1_700_000_000_000L);
        String b = ulid.generate(1_700_000_000_000L);
        assertTrue(!a.equals(b));
    }

    @Test
    public void lexicographicOrderMatchesTimeOrder() {
        Ulid ulid = new Ulid(new Random(3));
        List<String> generated = new ArrayList<String>();
        long base = 1_600_000_000_000L;
        for (int i = 0; i < 50; i++) {
            generated.add(ulid.generate(base + i * 1000L));
        }
        for (int i = 1; i < generated.size(); i++) {
            assertTrue("Earlier timestamp should sort first: "
                            + generated.get(i - 1) + " vs " + generated.get(i),
                    generated.get(i).compareTo(generated.get(i - 1)) > 0);
        }
    }

    @Test
    public void clockMovingBackwardsStaysMonotonic() {
        Ulid ulid = new Ulid(new Random(5));
        String first = ulid.generate(1_700_000_000_000L);
        String backwards = ulid.generate(1_699_999_999_000L);
        assertTrue("Backwards clock must not produce a smaller ULID",
                backwards.compareTo(first) > 0);
    }
}
