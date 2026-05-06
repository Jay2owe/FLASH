package flash.pipeline.decontamination;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpectralDecontaminationConfigTest {

    @Test
    public void validationRejectsTargetAsBleedThrough() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE);
        config.setBleedThroughChannelIndexes(indexes(0));

        List<String> errors = config.validate(3);

        assertTrue(contains(errors, "Target channel cannot also be selected as a bleed-through channel."));
    }

    @Test
    public void validationRejectsTargetAsAutofluorescence() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(1);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE);
        config.setAutofluorescenceChannelIndexes(indexes(1));

        List<String> errors = config.validate(3);

        assertTrue(contains(errors, "Target channel cannot also be selected as an autofluorescence channel."));
    }

    @Test
    public void validationAllowsSameNonTargetChannelAsBleedThroughAndAutofluorescence() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE);
        config.setBleedThroughChannelIndexes(indexes(1));
        config.setAutofluorescenceChannelIndexes(indexes(1));

        assertTrue(config.validate(3).isEmpty());
    }

    @Test
    public void validationAllowsZeroContaminantsForObjectScoring() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS);

        assertTrue(config.validate(2).isEmpty());
    }

    @Test
    public void validationRejectsZeroContaminantsForCleanedImageGoal() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE);

        List<String> errors = config.validate(2);

        assertTrue(contains(errors, "Select at least one bleed-through or autofluorescence channel for this goal."));
    }

    @Test
    public void validationRejectsExcludedContaminantOverlap() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE);
        config.setBleedThroughChannelIndexes(indexes(1));
        config.setExcludedChannelIndexes(indexes(1));

        List<String> errors = config.validate(3);

        assertTrue(contains(errors, "Excluded channel cannot also be selected as a bleed-through channel."));
    }

    @Test
    public void validationRejectsOutOfRangeIndexes() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE);
        config.setBleedThroughChannelIndexes(indexes(4));

        List<String> errors = config.validate(3);

        assertFalse(errors.isEmpty());
        assertTrue(contains(errors, "Bleed-through channel index is outside the channel list."));
    }

    private static boolean contains(List<String> errors, String expected) {
        for (String error : errors) {
            if (expected.equals(error)) return true;
        }
        return false;
    }

    private static List<Integer> indexes(int... values) {
        List<Integer> indexes = new ArrayList<Integer>();
        for (int value : values) {
            indexes.add(Integer.valueOf(value));
        }
        return indexes;
    }
}
