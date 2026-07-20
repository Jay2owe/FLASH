package flash.pipeline.click;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClickStore {
    public enum Verdict { POSITIVE, NEGATIVE }

    public static final class Click {
        public final String imageName;
        public final int channelOneBased;
        public final int label;
        public final int z;
        public final double x;
        public final double y;
        public final Verdict verdict;
        public final long timestampMs;

        public Click(String imageName, int channelOneBased, int label, int z,
                     double x, double y, Verdict verdict, long timestampMs) {
            this.imageName = imageName == null ? "" : imageName;
            this.channelOneBased = channelOneBased;
            this.label = label;
            this.z = z;
            this.x = x;
            this.y = y;
            this.verdict = verdict == null ? Verdict.NEGATIVE : verdict;
            this.timestampMs = timestampMs;
        }
    }

    private final Map<String, List<Click>> byChannelKey =
            new LinkedHashMap<String, List<Click>>();

    public synchronized void add(Click c) {
        if (c == null || c.label <= 0 || c.channelOneBased <= 0) return;
        clearForObject(c.imageName, c.channelOneBased, c.label);
        String key = key(c.imageName, c.channelOneBased);
        List<Click> clicks = byChannelKey.get(key);
        if (clicks == null) {
            clicks = new ArrayList<Click>();
            byChannelKey.put(key, clicks);
        }
        clicks.add(c);
    }

    public synchronized void clearForObject(String imageName, int channel, int label) {
        if (channel <= 0 || label <= 0) return;
        String key = key(imageName, channel);
        List<Click> clicks = byChannelKey.get(key);
        if (clicks == null) return;
        for (Iterator<Click> it = clicks.iterator(); it.hasNext();) {
            Click click = it.next();
            if (click != null && click.label == label) {
                it.remove();
            }
        }
        if (clicks.isEmpty()) {
            byChannelKey.remove(key);
        }
    }

    public synchronized List<Click> all() {
        List<Click> out = new ArrayList<Click>();
        for (List<Click> clicks : byChannelKey.values()) {
            out.addAll(clicks);
        }
        return out;
    }

    public synchronized List<Click> forChannel(int channel) {
        List<Click> out = new ArrayList<Click>();
        if (channel <= 0) return out;
        for (List<Click> clicks : byChannelKey.values()) {
            for (Click click : clicks) {
                if (click.channelOneBased == channel) {
                    out.add(click);
                }
            }
        }
        return out;
    }

    public synchronized List<Click> forImageAndChannel(String imageName, int channel) {
        List<Click> clicks = byChannelKey.get(key(imageName, channel));
        return clicks == null ? new ArrayList<Click>() : new ArrayList<Click>(clicks);
    }

    public synchronized List<Click> positive() {
        return withVerdict(Verdict.POSITIVE);
    }

    public synchronized List<Click> negative() {
        return withVerdict(Verdict.NEGATIVE);
    }

    private List<Click> withVerdict(Verdict verdict) {
        List<Click> out = new ArrayList<Click>();
        for (List<Click> clicks : byChannelKey.values()) {
            for (Click click : clicks) {
                if (click.verdict == verdict) {
                    out.add(click);
                }
            }
        }
        return out;
    }

    private static String key(String imageName, int channel) {
        return (imageName == null ? "" : imageName) + "::" + channel;
    }
}
