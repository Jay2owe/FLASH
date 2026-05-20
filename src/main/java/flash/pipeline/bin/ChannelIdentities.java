package flash.pipeline.bin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persisted marker identities for channels in a .bin directory.
 */
public final class ChannelIdentities {

    private final List<Entry> entries;

    public ChannelIdentities(List<Entry> entries) {
        this.entries = entries == null
                ? Collections.<Entry>emptyList()
                : Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public List<Entry> entries() {
        return getEntries();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Entry findByChannelIndex(int channelIndex) {
        for (Entry entry : entries) {
            if (entry.getChannelIndex() == channelIndex) {
                return entry;
            }
        }
        return null;
    }

    public static final class Entry {
        private final int channelIndex;
        private final String markerId;
        private final String shape;
        private final boolean crowdingSensitive;

        public Entry(int channelIndex, String markerId, String shape, boolean crowdingSensitive) {
            this.channelIndex = channelIndex;
            this.markerId = markerId == null ? "" : markerId;
            this.shape = shape == null ? "" : shape;
            this.crowdingSensitive = crowdingSensitive;
        }

        public int getChannelIndex() {
            return channelIndex;
        }

        public int channelIndex() {
            return getChannelIndex();
        }

        public String getMarkerId() {
            return markerId;
        }

        public String markerId() {
            return getMarkerId();
        }

        public String getShape() {
            return shape;
        }

        public String shape() {
            return getShape();
        }

        public boolean isCrowdingSensitive() {
            return crowdingSensitive;
        }

        public boolean crowdingSensitive() {
            return isCrowdingSensitive();
        }
    }
}
