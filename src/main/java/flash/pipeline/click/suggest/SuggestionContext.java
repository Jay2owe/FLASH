package flash.pipeline.click.suggest;

import flash.pipeline.click.ClickStore;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SuggestionContext {
    public final ImagePlus channelImage;
    public final ImagePlus labelImage;
    public final ImagePlus auxImage;
    public final List<ClickStore.Click> negativeClicks;
    public final List<ClickStore.Click> positiveClicks;
    public final Map<String, Double> currentParams;

    public SuggestionContext(ImagePlus channelImage,
                             ImagePlus labelImage,
                             ImagePlus auxImage,
                             List<ClickStore.Click> negativeClicks,
                             List<ClickStore.Click> positiveClicks,
                             Map<String, Double> currentParams) {
        this.channelImage = channelImage;
        this.labelImage = labelImage;
        this.auxImage = auxImage;
        this.negativeClicks = immutableClicks(negativeClicks);
        this.positiveClicks = immutableClicks(positiveClicks);
        this.currentParams = currentParams == null
                ? Collections.<String, Double>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Double>(currentParams));
    }

    private static List<ClickStore.Click> immutableClicks(List<ClickStore.Click> clicks) {
        if (clicks == null || clicks.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<ClickStore.Click>(clicks));
    }
}
