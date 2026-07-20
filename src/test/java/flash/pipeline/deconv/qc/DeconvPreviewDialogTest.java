package flash.pipeline.deconv.qc;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeconvPreviewDialogTest {

    @After
    public void tearDown() {
        DeconvPreviewDialog.resetForTest();
    }

    @Test
    public void headlessModeReturnsRunFullBatchWithoutShowingDialog() {
        DeconvPreviewDialog.setHeadlessProbeForTest(new DeconvPreviewDialog.HeadlessProbe() {
            @Override
            public boolean isHeadless() {
                return true;
            }
        });

        assertEquals(DeconvPreviewDialog.Decision.RUN_FULL_BATCH,
                DeconvPreviewDialog.show(null, false));
    }

    @Test
    public void headlessModeShortCircuitsEvenWithContent() {
        DeconvPreviewDialog.setHeadlessProbeForTest(new DeconvPreviewDialog.HeadlessProbe() {
            @Override
            public boolean isHeadless() {
                return true;
            }
        });

        // Content present, but headless must return without constructing any Swing UI.
        DeconvPreviewDialog.PreviewContent content =
                new DeconvPreviewDialog.PreviewContent(null, null, "Raw", "Deconvolved");
        assertEquals(DeconvPreviewDialog.Decision.RUN_FULL_BATCH,
                DeconvPreviewDialog.show(content, false));
    }

    @Test
    public void skipPreviewReturnsRunFullBatch() {
        DeconvPreviewDialog.setHeadlessProbeForTest(new DeconvPreviewDialog.HeadlessProbe() {
            @Override
            public boolean isHeadless() {
                return false;
            }
        });

        assertEquals(DeconvPreviewDialog.Decision.RUN_FULL_BATCH,
                DeconvPreviewDialog.show(null, true));
    }

    @Test
    public void skipPreviewShortCircuitsEvenWithContent() {
        DeconvPreviewDialog.setHeadlessProbeForTest(new DeconvPreviewDialog.HeadlessProbe() {
            @Override
            public boolean isHeadless() {
                return false;
            }
        });

        // skipPreview=true must bypass the UI even when content is available (unattended runs).
        DeconvPreviewDialog.PreviewContent content =
                new DeconvPreviewDialog.PreviewContent(null, null, "Raw", "Deconvolved");
        assertEquals(DeconvPreviewDialog.Decision.RUN_FULL_BATCH,
                DeconvPreviewDialog.show(content, true));
    }

    @Test
    public void singleChannelConstructorMirrorsFirstChannel() {
        DeconvPreviewDialog.PreviewContent content =
                new DeconvPreviewDialog.PreviewContent(null, null, "Raw", "Deconvolved");
        assertEquals(1, content.channels.size());
        assertEquals("Raw", content.rawLabel);
        assertEquals("Deconvolved", content.deconvolvedLabel);
        // Convenience fields must mirror the first (only) channel for legacy readers.
        assertSame(content.rawStack, content.channels.get(0).rawStack);
        assertSame(content.deconvolvedStack, content.channels.get(0).deconvolvedStack);
    }

    @Test
    public void multiChannelConvenienceFieldsMirrorFirstChannel() {
        DeconvPreviewDialog.ChannelPreview first = new DeconvPreviewDialog.ChannelPreview(
                null, null, "Raw", "Deconvolved (DAPI)", "DAPI", "Blue");
        DeconvPreviewDialog.ChannelPreview second = new DeconvPreviewDialog.ChannelPreview(
                null, null, "Raw", "Deconvolved (GFP)", "GFP", "Green");
        DeconvPreviewDialog.PreviewContent content =
                new DeconvPreviewDialog.PreviewContent(Arrays.asList(first, second));

        assertEquals(2, content.channels.size());
        // First-channel mirror keeps single-channel readers working.
        assertEquals("Deconvolved (DAPI)", content.deconvolvedLabel);
        assertEquals("GFP", content.channels.get(1).channelName);
        assertEquals("Green", content.channels.get(1).channelLutName);
    }

    @Test
    public void multiChannelContentShortCircuitsWhenHeadless() {
        DeconvPreviewDialog.setHeadlessProbeForTest(new DeconvPreviewDialog.HeadlessProbe() {
            @Override
            public boolean isHeadless() {
                return true;
            }
        });
        DeconvPreviewDialog.PreviewContent content = new DeconvPreviewDialog.PreviewContent(Arrays.asList(
                new DeconvPreviewDialog.ChannelPreview(null, null, "Raw", "Deconvolved (DAPI)", "DAPI", "Blue"),
                new DeconvPreviewDialog.ChannelPreview(null, null, "Raw", "Deconvolved (GFP)", "GFP", "Green")));
        assertEquals(DeconvPreviewDialog.Decision.RUN_FULL_BATCH,
                DeconvPreviewDialog.show(content, false));
    }

    @Test
    public void emptyChannelListIsRejected() {
        try {
            new DeconvPreviewDialog.PreviewContent(Collections.<DeconvPreviewDialog.ChannelPreview>emptyList());
            fail("an empty channel list must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: a preview must contain at least one channel.
        }
    }

    // --- Grey LUT toggle label logic (the user-reported "grey <-> grays" fix) ---

    private static DeconvPreviewDialog.PreviewContent content(String... luts) {
        java.util.List<DeconvPreviewDialog.ChannelPreview> channels =
                new java.util.ArrayList<DeconvPreviewDialog.ChannelPreview>();
        for (int i = 0; i < luts.length; i++) {
            channels.add(new DeconvPreviewDialog.ChannelPreview(
                    null, null, "Raw", "Deconvolved", "Ch" + i, luts[i]));
        }
        return new DeconvPreviewDialog.PreviewContent(channels);
    }

    @Test
    public void singleColourChannelTogglesBetweenItsLutAndGrey() {
        // The fix: the toggle must offer the real channel colour, not "Grays".
        assertEquals("Blue LUT", DeconvPreviewDialog.colourToggleLabel(content("Blue")));
        assertFalse(DeconvPreviewDialog.allGrayLuts(content("Blue")));
    }

    @Test
    public void multipleColourChannelsUseGenericChannelLutsLabel() {
        assertEquals("Channel LUTs", DeconvPreviewDialog.colourToggleLabel(content("Blue", "Green")));
        assertFalse(DeconvPreviewDialog.allGrayLuts(content("Blue", "Green")));
    }

    @Test
    public void grayscaleChannelsDisableTheToggle() {
        // Every channel grey (incl. null/"Grays"/"grey") => nothing to toggle to.
        assertTrue(DeconvPreviewDialog.allGrayLuts(content("Grays")));
        assertTrue(DeconvPreviewDialog.allGrayLuts(content((String) null)));
        assertTrue(DeconvPreviewDialog.allGrayLuts(content("grey", "Grays")));
        // A single coloured channel among greys keeps the toggle live.
        assertFalse(DeconvPreviewDialog.allGrayLuts(content("Grays", "Blue")));
    }
}
