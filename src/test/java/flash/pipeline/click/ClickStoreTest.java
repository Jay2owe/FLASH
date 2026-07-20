package flash.pipeline.click;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class ClickStoreTest {

    @Test
    public void addAndFilterByChannelAndVerdict() {
        ClickStore store = new ClickStore();
        store.add(click("image-a", 1, 10, ClickStore.Verdict.NEGATIVE, 100L));
        store.add(click("image-b", 2, 20, ClickStore.Verdict.POSITIVE, 200L));
        store.add(click("image-a", 2, 30, ClickStore.Verdict.NEGATIVE, 300L));

        assertEquals(3, store.all().size());
        assertEquals(1, store.forChannel(1).size());
        assertEquals(2, store.forChannel(2).size());
        assertEquals(1, store.forImageAndChannel("image-a", 1).size());
        assertEquals(1, store.positive().size());
        assertEquals(2, store.negative().size());
    }

    @Test
    public void clearForObjectRemovesOnlyThatImageChannelLabel() {
        ClickStore store = new ClickStore();
        store.add(click("image-a", 1, 7, ClickStore.Verdict.NEGATIVE, 100L));
        store.add(click("image-a", 2, 7, ClickStore.Verdict.POSITIVE, 200L));
        store.add(click("image-b", 1, 7, ClickStore.Verdict.POSITIVE, 300L));

        store.clearForObject("image-a", 1, 7);

        assertEquals(2, store.all().size());
        assertEquals(0, store.forImageAndChannel("image-a", 1).size());
        assertEquals(1, store.forImageAndChannel("image-a", 2).size());
        assertEquals(1, store.forImageAndChannel("image-b", 1).size());
    }

    @Test
    public void addForSameObjectCollapsesToMostRecentVerdict() {
        ClickStore store = new ClickStore();
        store.add(click("image-a", 2, 47, ClickStore.Verdict.NEGATIVE, 100L));
        store.add(click("image-a", 2, 47, ClickStore.Verdict.POSITIVE, 200L));

        List<ClickStore.Click> clicks = store.all();
        assertEquals(1, clicks.size());
        assertEquals(ClickStore.Verdict.POSITIVE, clicks.get(0).verdict);
        assertEquals(200L, clicks.get(0).timestampMs);
        assertEquals(1, store.positive().size());
        assertEquals(0, store.negative().size());
    }

    private static ClickStore.Click click(String image, int channel, int label,
                                          ClickStore.Verdict verdict, long timestamp) {
        return new ClickStore.Click(image, channel, label, 3,
                12.5, 20.25, verdict, timestamp);
    }
}
