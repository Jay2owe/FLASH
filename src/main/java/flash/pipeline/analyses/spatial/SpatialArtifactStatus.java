package flash.pipeline.analyses.spatial;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SpatialArtifactStatus {
    private final Map<SectionChannelKey, EnumSet<SubAnalysis>> done;
    private final Set<String> allChannels;
    private final Set<SectionKey> allSections;

    SpatialArtifactStatus(Map<SectionChannelKey, EnumSet<SubAnalysis>> done,
                          List<String> channelNames,
                          List<SectionKey> sections) {
        this.done = immutableDoneMap(done);
        this.allChannels = immutableChannelSet(channelNames);
        this.allSections = immutableSectionSet(sections);
    }

    public boolean isDone(SubAnalysis sub, SectionKey section, String channel) {
        if (sub == null || section == null || channel == null) {
            return false;
        }
        EnumSet<SubAnalysis> completed = done.get(new SectionChannelKey(section, channel));
        return completed != null && completed.contains(sub);
    }

    public boolean isFullyDone(SubAnalysis sub) {
        if (sub == null || allChannels.isEmpty() || allSections.isEmpty()) {
            return false;
        }
        for (String channel : allChannels) {
            for (SectionKey section : allSections) {
                if (!isDone(sub, section, channel)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean isPartiallyDone(SubAnalysis sub) {
        if (sub == null) {
            return false;
        }
        boolean anyDone = false;
        boolean anyMissing = false;
        for (String channel : allChannels) {
            for (SectionKey section : allSections) {
                if (isDone(sub, section, channel)) {
                    anyDone = true;
                } else {
                    anyMissing = true;
                }
            }
        }
        return anyDone && anyMissing;
    }

    public Set<SectionChannelKey> missingPairs(SubAnalysis sub) {
        LinkedHashSet<SectionChannelKey> missing = new LinkedHashSet<SectionChannelKey>();
        if (sub == null) {
            return Collections.unmodifiableSet(missing);
        }
        for (String channel : allChannels) {
            for (SectionKey section : allSections) {
                if (!isDone(sub, section, channel)) {
                    missing.add(new SectionChannelKey(section, channel));
                }
            }
        }
        return Collections.unmodifiableSet(missing);
    }

    public boolean isFullyDoneForChannel(SubAnalysis sub, String channel) {
        if (sub == null || channel == null || allSections.isEmpty()) {
            return false;
        }
        for (SectionKey section : allSections) {
            if (!isDone(sub, section, channel)) {
                return false;
            }
        }
        return true;
    }

    public int countDone(SubAnalysis sub) {
        if (sub == null) {
            return 0;
        }
        int count = 0;
        for (String channel : allChannels) {
            for (SectionKey section : allSections) {
                if (isDone(sub, section, channel)) {
                    count++;
                }
            }
        }
        return count;
    }

    public int totalPairs() {
        return allChannels.size() * allSections.size();
    }

    public Set<String> channels() {
        return allChannels;
    }

    public Set<SectionKey> sections() {
        return allSections;
    }

    private static Map<SectionChannelKey, EnumSet<SubAnalysis>> immutableDoneMap(
            Map<SectionChannelKey, EnumSet<SubAnalysis>> source) {
        LinkedHashMap<SectionChannelKey, EnumSet<SubAnalysis>> copy =
                new LinkedHashMap<SectionChannelKey, EnumSet<SubAnalysis>>();
        if (source != null) {
            for (Map.Entry<SectionChannelKey, EnumSet<SubAnalysis>> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                copy.put(entry.getKey(), EnumSet.copyOf(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Set<String> immutableChannelSet(List<String> channelNames) {
        LinkedHashSet<String> channels = new LinkedHashSet<String>();
        if (channelNames != null) {
            for (String channelName : channelNames) {
                if (channelName != null) {
                    channels.add(channelName);
                }
            }
        }
        return Collections.unmodifiableSet(channels);
    }

    private static Set<SectionKey> immutableSectionSet(List<SectionKey> sections) {
        LinkedHashSet<SectionKey> out = new LinkedHashSet<SectionKey>();
        if (sections != null) {
            for (SectionKey section : sections) {
                if (section != null) {
                    out.add(section);
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public static final class SectionChannelKey {
        private final SectionKey section;
        private final String channel;

        public SectionChannelKey(SectionKey section, String channel) {
            this.section = section;
            this.channel = channel == null ? "" : channel;
        }

        public SectionKey section() {
            return section;
        }

        public String channel() {
            return channel;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SectionChannelKey)) return false;
            SectionChannelKey that = (SectionChannelKey) o;
            return Objects.equals(section, that.section)
                    && Objects.equals(channel, that.channel);
        }

        @Override
        public int hashCode() {
            return Objects.hash(section, channel);
        }

        @Override
        public String toString() {
            return String.valueOf(section) + "|" + channel;
        }
    }
}
