package flash.pipeline.help;

import java.util.Arrays;

/**
 * Immutable recommendation returned by {@link AnalysisAdvisor}.
 */
public final class AdvisorResult {

    private final String title;
    private final String paragraph;
    private final String suggestedRecipe;
    private final int[] suggestedAnalysisIndices;

    public AdvisorResult(String title, String paragraph,
                         String suggestedRecipe, int[] suggestedAnalysisIndices) {
        this.title = title == null ? "" : title;
        this.paragraph = paragraph == null ? "" : paragraph;
        this.suggestedRecipe = suggestedRecipe;
        this.suggestedAnalysisIndices = suggestedAnalysisIndices == null
                ? new int[0]
                : Arrays.copyOf(suggestedAnalysisIndices, suggestedAnalysisIndices.length);
    }

    public String getTitle() {
        return title;
    }

    public String title() {
        return title;
    }

    public String getParagraph() {
        return paragraph;
    }

    public String paragraph() {
        return paragraph;
    }

    public String getSuggestedRecipe() {
        return suggestedRecipe;
    }

    public String suggestedRecipe() {
        return suggestedRecipe;
    }

    public int[] getSuggestedAnalysisIndices() {
        return Arrays.copyOf(suggestedAnalysisIndices, suggestedAnalysisIndices.length);
    }

    public int[] suggestedAnalysisIndices() {
        return getSuggestedAnalysisIndices();
    }
}
