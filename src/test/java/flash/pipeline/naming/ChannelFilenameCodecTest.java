package flash.pipeline.naming;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link ChannelFilenameCodec}: safe encoding, reversible
 * round-trips, edge cases, and reserved-name handling.
 */
public class ChannelFilenameCodecTest {

    // ── safe names pass through unchanged ────────────────────────────

    @Test
    public void safeNameUnchanged() {
        assertEquals("DAPI", ChannelFilenameCodec.toSafe("DAPI"));
        assertEquals("Iba1", ChannelFilenameCodec.toSafe("Iba1"));
        assertEquals("MAP2", ChannelFilenameCodec.toSafe("MAP2"));
        assertEquals("NeuN-488", ChannelFilenameCodec.toSafe("NeuN-488"));
    }

    @Test
    public void isSafeReturnsTrueForSafeNames() {
        assertTrue(ChannelFilenameCodec.isSafe("DAPI"));
        assertTrue(ChannelFilenameCodec.isSafe("GFP"));
        assertTrue(ChannelFilenameCodec.isSafe("Channel_1"));
    }

    // ── forbidden characters are encoded ─────────────────────────────

    @Test
    public void slashEncoded() {
        assertEquals("GFP%2FYFP", ChannelFilenameCodec.toSafe("GFP/YFP"));
    }

    @Test
    public void backslashEncoded() {
        assertEquals("A%5CB", ChannelFilenameCodec.toSafe("A\\B"));
    }

    @Test
    public void colonEncoded() {
        assertEquals("Ch%3A1", ChannelFilenameCodec.toSafe("Ch:1"));
    }

    @Test
    public void allForbiddenCharsEncoded() {
        String raw = "a\\b/c:d*e?f\"g<h>i|j";
        String safe = ChannelFilenameCodec.toSafe(raw);
        assertFalse(safe.contains("\\"));
        assertFalse(safe.contains("/"));
        assertFalse(safe.contains(":"));
        assertFalse(safe.contains("*"));
        assertFalse(safe.contains("?"));
        assertFalse(safe.contains("\""));
        assertFalse(safe.contains("<"));
        assertFalse(safe.contains(">"));
        assertFalse(safe.contains("|"));
    }

    @Test
    public void percentItselfIsEncoded() {
        assertEquals("50%2525", ChannelFilenameCodec.toSafe("50%25"));
        assertFalse(ChannelFilenameCodec.isSafe("50%25"));
    }

    // ── round-trip ───────────────────────────────────────────────────

    @Test
    public void roundTripSafe() {
        assertRoundTrip("DAPI");
        assertRoundTrip("Iba1");
    }

    @Test
    public void roundTripUnsafe() {
        assertRoundTrip("GFP/YFP");
        assertRoundTrip("Ch:1");
        assertRoundTrip("A\\B");
        assertRoundTrip("a*b?c\"d<e>f|g");
        assertRoundTrip("50%25");
    }

    @Test
    public void roundTripTrailingDot() {
        assertRoundTrip("Channel.");
        assertRoundTrip("Test...");
    }

    @Test
    public void roundTripTrailingSpace() {
        assertRoundTrip("Channel ");
        assertRoundTrip("Test   ");
    }

    // ── trailing dot / space ─────────────────────────────────────────

    @Test
    public void trailingDotEncoded() {
        String safe = ChannelFilenameCodec.toSafe("Channel.");
        assertFalse("Should not end with dot", safe.endsWith("."));
    }

    @Test
    public void trailingSpaceEncoded() {
        String safe = ChannelFilenameCodec.toSafe("Channel ");
        assertFalse("Should not end with space", safe.endsWith(" "));
    }

    // ── reserved device names ────────────────────────────────────────

    @Test
    public void reservedNameEncoded() {
        String safe = ChannelFilenameCodec.toSafe("CON");
        assertNotEquals("CON", safe);
        assertEquals("CON", ChannelFilenameCodec.toRaw(safe));
    }

    @Test
    public void reservedNameWithExtension() {
        String safe = ChannelFilenameCodec.toSafe("NUL.txt");
        assertNotEquals("NUL.txt", safe);
        assertEquals("NUL.txt", ChannelFilenameCodec.toRaw(safe));
    }

    @Test
    public void reservedNameCaseInsensitive() {
        assertNotEquals("con", ChannelFilenameCodec.toSafe("con"));
        assertEquals("con", ChannelFilenameCodec.toRaw(ChannelFilenameCodec.toSafe("con")));
    }

    @Test
    public void isSafeReturnsFalseForReserved() {
        assertFalse(ChannelFilenameCodec.isSafe("CON"));
        assertFalse(ChannelFilenameCodec.isSafe("nul"));
        assertFalse(ChannelFilenameCodec.isSafe("COM1"));
    }

    // ── null/empty ───────────────────────────────────────────────────

    @Test
    public void nullPassesThrough() {
        assertNull(ChannelFilenameCodec.toSafe(null));
        assertNull(ChannelFilenameCodec.toRaw(null));
    }

    @Test
    public void emptyPassesThrough() {
        assertEquals("", ChannelFilenameCodec.toSafe(""));
        assertEquals("", ChannelFilenameCodec.toRaw(""));
    }

    // ── helper ───────────────────────────────────────────────────────

    private static void assertRoundTrip(String raw) {
        String safe = ChannelFilenameCodec.toSafe(raw);
        String decoded = ChannelFilenameCodec.toRaw(safe);
        assertEquals("Round-trip failed for: " + raw, raw, decoded);
    }
}
