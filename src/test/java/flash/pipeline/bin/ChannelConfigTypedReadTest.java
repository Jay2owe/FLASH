package flash.pipeline.bin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Stage 03: typed read states, backup-before-delete, and rolling backup. */
public class ChannelConfigTypedReadTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void readResultAbsentWhenNoFile() throws Exception {
        ChannelConfigIO.ReadResult result = ChannelConfigIO.readResult(temp.newFolder("absent"));
        assertEquals(ChannelConfigIO.ReadState.ABSENT, result.state);
        assertNull(result.config);
    }

    @Test
    public void readResultOkWhenCompleteConfig() throws Exception {
        File settingsDir = temp.newFolder("ok");
        ChannelConfigIO.write(settingsDir, ChannelConfigIORoundTripTest.committedConfig("DAPI", "Blue"));

        ChannelConfigIO.ReadResult result = ChannelConfigIO.readResult(settingsDir);

        assertEquals(ChannelConfigIO.ReadState.OK, result.state);
        assertNotNull(result.config);
    }

    @Test
    public void readResultIncompleteWhenNotCommitted() throws Exception {
        File settingsDir = temp.newFolder("incomplete");
        ChannelConfig cfg = ChannelConfigIORoundTripTest.committedConfig("DAPI", "Blue");
        cfg.channels.get(0).status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.CONFIGURED);
        ChannelConfigIO.write(settingsDir, cfg);

        ChannelConfigIO.ReadResult result = ChannelConfigIO.readResult(settingsDir);

        assertEquals(ChannelConfigIO.ReadState.INCOMPLETE, result.state);
        assertNotNull(result.config);
    }

    @Test
    public void readResultCorruptOnGarbage() throws Exception {
        File settingsDir = temp.newFolder("corrupt");
        Files.write(new File(settingsDir, ChannelConfigIO.FILE_NAME).toPath(),
                "{not valid json".getBytes(StandardCharsets.UTF_8));

        ChannelConfigIO.ReadResult result = ChannelConfigIO.readResult(settingsDir);

        assertEquals(ChannelConfigIO.ReadState.CORRUPT, result.state);
        assertNull(result.config);
    }

    @Test
    public void readResultNewerVersionWhenSchemaAhead() throws Exception {
        File settingsDir = temp.newFolder("newer");
        int ahead = ChannelConfigCodec.schemaVersion() + 1;
        Files.write(new File(settingsDir, ChannelConfigIO.FILE_NAME).toPath(),
                ("{\"schemaVersion\":" + ahead + ",\"channels\":[]}").getBytes(StandardCharsets.UTF_8));

        ChannelConfigIO.ReadResult result = ChannelConfigIO.readResult(settingsDir);

        assertEquals(ChannelConfigIO.ReadState.NEWER_VERSION, result.state);
        assertNull(result.config);
    }

    @Test
    public void backupThenDeleteKeepsCorruptCopyAndRemovesOriginal() throws Exception {
        File settingsDir = temp.newFolder("backup-delete");
        ChannelConfigIO.write(settingsDir, ChannelConfigIORoundTripTest.committedConfig("DAPI", "Blue"));

        assertTrue(ChannelConfigIO.backupThenDelete(settingsDir));

        assertFalse(ChannelConfigIO.exists(settingsDir));
        File[] corrupt = settingsDir.listFiles(new java.io.FilenameFilter() {
            @Override public boolean accept(File dir, String name) {
                return name.startsWith("channel_config.corrupt-") && name.endsWith(".json");
            }
        });
        assertNotNull(corrupt);
        assertTrue("expected a kept .corrupt copy", corrupt.length >= 1);
    }

    @Test
    public void backupThenDeleteNoFileSucceeds() throws Exception {
        assertTrue(ChannelConfigIO.backupThenDelete(temp.newFolder("nothing")));
    }

    @Test
    public void backupThenDeleteKeepsFileWhenMoveFails() throws Exception {
        // The never-bare-delete guarantee: if the backup move cannot complete, the
        // original config must be kept (not deleted), and the call reports failure.
        File settingsDir = temp.newFolder("backup-fail");
        ChannelConfigIO.write(settingsDir, ChannelConfigIORoundTripTest.committedConfig("DAPI", "Blue"));

        boolean result = ChannelConfigIO.backupThenDelete(settingsDir, new ChannelConfigIO.BackupMover() {
            @Override
            public void move(java.nio.file.Path source, java.nio.file.Path target) throws java.io.IOException {
                throw new java.io.IOException("simulated locked rename");
            }
        });

        assertFalse(result);
        assertTrue("config must be kept when the backup move fails",
                ChannelConfigIO.exists(settingsDir));
    }

    @Test
    public void writeKeepsRollingBackupOfPreviousGood() throws Exception {
        File settingsDir = temp.newFolder("rolling");
        ChannelConfig first = ChannelConfigIORoundTripTest.committedConfig("DAPI", "Blue");
        first.writerId = "first";
        ChannelConfigIO.write(settingsDir, first);
        ChannelConfig second = ChannelConfigIORoundTripTest.committedConfig("IBA1", "Green");
        second.writerId = "second";
        ChannelConfigIO.write(settingsDir, second);

        File bak = new File(settingsDir, ChannelConfigIO.BAK_FILE_NAME);
        assertTrue(bak.isFile());
        ChannelConfig bakCfg = ChannelConfigCodec.decode(
                new String(Files.readAllBytes(bak.toPath()), StandardCharsets.UTF_8));
        assertEquals("first", bakCfg.writerId);
    }

    @Test
    public void corruptPrimaryRecoversFromBackupViaRead() throws Exception {
        File settingsDir = temp.newFolder("recover");
        ChannelConfig good = ChannelConfigIORoundTripTest.committedConfig("DAPI", "Blue");
        good.writerId = "good";
        ChannelConfigIO.write(settingsDir, good);
        // Second write rolls the good copy into .bak.
        ChannelConfigIO.write(settingsDir, ChannelConfigIORoundTripTest.committedConfig("IBA1", "Green"));
        // Now damage the primary.
        Files.write(new File(settingsDir, ChannelConfigIO.FILE_NAME).toPath(),
                "{truncated".getBytes(StandardCharsets.UTF_8));

        ChannelConfig recovered = ChannelConfigIO.read(settingsDir);

        assertNotNull("corrupt primary should recover from .bak", recovered);
        assertEquals("good", recovered.writerId);
    }

    @Test
    public void readShimReturnsNullForAbsentAndNewerButConfigForOkIncomplete() throws Exception {
        assertNull(ChannelConfigIO.read(temp.newFolder("none")));

        File newer = temp.newFolder("shim-newer");
        Files.write(new File(newer, ChannelConfigIO.FILE_NAME).toPath(),
                ("{\"schemaVersion\":" + (ChannelConfigCodec.schemaVersion() + 1)
                        + ",\"channels\":[]}").getBytes(StandardCharsets.UTF_8));
        assertNull(ChannelConfigIO.read(newer));

        File ok = temp.newFolder("shim-ok");
        ChannelConfigIO.write(ok, ChannelConfigIORoundTripTest.committedConfig("DAPI", "Blue"));
        assertNotNull(ChannelConfigIO.read(ok));
    }
}
