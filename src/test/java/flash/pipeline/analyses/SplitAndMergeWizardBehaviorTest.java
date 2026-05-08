package flash.pipeline.analyses;

import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.bin.ChannelIdentitiesIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SplitAndMergeWizardBehaviorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void detectsAutofluorescenceMarkerIdentityAsBackgroundHint() {
        ChannelIdentities identities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "nuclei_dapi", "round", false),
                new ChannelIdentities.Entry(1, "autofluorescence", "background", false),
                new ChannelIdentities.Entry(2, "microglia_iba1", "complex", true)));

        assertEquals(1, SplitAndMergeImageChannelsAnalysis.detectAutofluorescenceChannel(identities, 3));
    }

    @Test
    public void readsAutofluorescenceHintFromProjectBinDirectory() throws Exception {
        File root = temp.newFolder("project");
        File binDir = new File(root, ".bin");
        ChannelIdentitiesIO.write(binDir, new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "nuclei_dapi", "round", false),
                new ChannelIdentities.Entry(1, "my_autofluorescence_control", "", false))));

        assertEquals(1, SplitAndMergeImageChannelsAnalysis.detectAutofluorescenceChannel(root, 2));
    }

    @Test
    public void ignoresOutOfRangeAutofluorescenceHint() {
        ChannelIdentities identities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(4, "autofluorescence", "background", false)));

        assertEquals(-1, SplitAndMergeImageChannelsAnalysis.detectAutofluorescenceChannel(identities, 3));
    }

    @Test
    public void presentationImagePresetsAreAbsent() {
        assertFalse(new File("src/main/resources/split_merge_presets").exists());
        assertFalse(new File("src/main/java/flash/pipeline/analyses/wizard/SplitMergePreset.java").exists());
        assertFalse(new File("src/main/java/flash/pipeline/analyses/wizard/SplitMergePresetIO.java").exists());
    }
}
