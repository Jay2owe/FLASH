package flash.pipeline.representative;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable configuration holder for the representative image figure workflow.
 * Later representative-image-figure stages add concrete fields here.
 */
public class RepresentativeFigureConfig {
    public RepresentativeStatistic statistic = RepresentativeStatistic.QUICK;
    public RepresentativeStatLoader.ExistingResultOption existingResult = null;
    public RepresentativeStatTable statTable = new RepresentativeStatTable();
    public RepresentativeSelection selection = null;
    public final Map<Integer, String> customDisplayRangesByChannel =
            new LinkedHashMap<Integer, String>();

    public String customDisplayRangeForChannel(int channelIndex) {
        if (channelIndex < 0) return null;
        return customDisplayRangesByChannel.get(Integer.valueOf(channelIndex));
    }

    public void setCustomDisplayRangeForChannel(int channelIndex, String token) {
        if (channelIndex < 0) return;
        Integer key = Integer.valueOf(channelIndex);
        if (token == null || token.trim().isEmpty()) {
            customDisplayRangesByChannel.remove(key);
        } else {
            customDisplayRangesByChannel.put(key, token.trim());
        }
    }

    public void clearCustomDisplayRanges() {
        customDisplayRangesByChannel.clear();
    }
}
