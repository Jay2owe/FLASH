package flash.pipeline.bin;

import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Stage 04: version gate, typed newer-version error, migration scaffolding. */
public class ChannelConfigMigrationTest {

    @Test
    public void currentVersionFileDecodesUnchangedAndIsNotMarkedMigrated() throws Exception {
        String json = "{"
                + "\"schemaVersion\":" + ChannelConfigCodec.schemaVersion() + ","
                + "\"writerId\":\"FLASH-test\","
                + "\"writtenAtMillis\":1,"
                + "\"channels\":[{\"index\":0,\"name\":\"DAPI\"}],"
                + "\"zSliceMode\":\"FULL\","
                + "\"zSliceSelections\":{},"
                + "\"clickCaptureUsed\":false"
                + "}";

        ChannelConfig cfg = ChannelConfigCodec.decode(json);

        assertEquals(ChannelConfigCodec.schemaVersion(), cfg.schemaVersion);
        assertEquals(ChannelConfigCodec.schemaVersion(), cfg.originalSchemaVersion);
        assertFalse("current-version file must not be flagged as migrated", cfg.migrated);
        assertEquals("DAPI", cfg.channels.get(0).name);
    }

    @Test
    public void newerVersionThrowsNewerSchemaException() throws Exception {
        int ahead = ChannelConfigCodec.schemaVersion() + 1;
        try {
            ChannelConfigCodec.decode("{\"schemaVersion\":" + ahead + ",\"channels\":[]}");
            fail("Expected NewerSchemaException");
        } catch (NewerSchemaException e) {
            assertEquals(ahead, e.getRequestedVersion());
            assertEquals(ChannelConfigCodec.schemaVersion(), e.getSupportedVersion());
        }
    }

    @Test
    public void upgradeIsAdditiveIdentityAtCurrentVersion() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", Integer.valueOf(ChannelConfigCodec.schemaVersion()));
        root.put("writerId", "FLASH");
        root.put("futureKey", "keep-me");

        Map<String, Object> upgraded =
                ChannelConfigMigrations.upgrade(root, ChannelConfigCodec.schemaVersion());

        // Additive contract: no existing key is removed or repurposed.
        assertEquals("FLASH", upgraded.get("writerId"));
        assertEquals("keep-me", upgraded.get("futureKey"));
        assertTrue(upgraded.containsKey("schemaVersion"));
    }

    @Test
    public void unknownExtraKeysSurviveReadWriteCycle() throws Exception {
        String json = "{"
                + "\"schemaVersion\":" + ChannelConfigCodec.schemaVersion() + ","
                + "\"channels\":[{\"index\":0,\"name\":\"DAPI\",\"futureChannelKey\":\"keep\"}],"
                + "\"zSliceMode\":\"FULL\","
                + "\"zSliceSelections\":{},"
                + "\"clickCaptureUsed\":false,"
                + "\"futureRootKey\":{\"nested\":true}"
                + "}";

        String encoded = ChannelConfigCodec.encode(ChannelConfigCodec.decode(json));
        ChannelConfig back = ChannelConfigCodec.decode(encoded);

        assertEquals("keep", back.channels.get(0).extras.get("futureChannelKey"));
        assertTrue(back.extras.containsKey("futureRootKey"));
    }

    @Test
    public void belowOneVersionIsRejectedAsPlainIOException() throws Exception {
        try {
            ChannelConfigCodec.decode("{\"schemaVersion\":0,\"channels\":[]}");
            fail("Expected IOException");
        } catch (NewerSchemaException e) {
            fail("version 0 is not a newer-version case");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("schemaVersion"));
        }
    }
}
