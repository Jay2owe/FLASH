package flash.pipeline.runrecord;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RunRecordCodecRoundTripTest {

    @Test
    public void emptyRecordRoundTrip() throws Exception {
        RunRecord record = new RunRecord();

        RunRecord back = RunRecordCodec.decode(RunRecordCodec.encode(record));

        assertEquals(1, back.schemaVersion);
        assertEquals("", back.runId);
        assertEquals("ok", back.status);
        assertEquals(-1, back.analysisIndex);
        assertTrue(back.inputs.isEmpty());
        assertTrue(back.outputs.isEmpty());
        assertTrue(back.messages.isEmpty());
        assertTrue(back.parameters.isEmpty());
    }

    @Test
    public void populatedRecordRoundTrip() throws Exception {
        RunRecord record = new RunRecord();
        record.runId = "01HZX3R8K2QABCDEFGHJKMNPQR";
        record.parentRunId = "01HZX0000000000000000000AB";
        record.startedAtMillis = 1_716_912_000_000L;
        record.finishedAtMillis = 1_716_912_030_000L;
        record.status = RunRecord.STATUS_WARN;
        record.analysis = "ThreeDObjectAnalysis";
        record.analysisIndex = 4;
        record.analysisLabel = "3D Object Analysis";
        record.flashVersion = "4.0.0";
        record.fijiBuild = "1.54f";
        record.jdkVersion = "1.8.0";
        record.osName = "Windows 11";
        record.biofVersion = "7.3.0";
        record.projectFileHash = "abc123";
        record.projectRoot = "D:/projects/cohortA";
        record.outputRoot = "D:/projects/cohortA";
        record.parameters.put("skipExisting", Boolean.TRUE);
        record.parameters.put("parallelThreads", Long.valueOf(8L));
        record.parameters.put("note", "fast mode");

        RunRecord.InputItem input = new RunRecord.InputItem();
        input.path = "D:/raw/Exp1-03_LH_Hb.lif";
        input.seriesIndex = 1;
        input.animalId = "03";
        input.hemisphere = "LH";
        input.region = "Hb";
        input.condition = "WT";
        input.fingerprintMode = "fast";
        input.fingerprint = "deadbeef";
        input.sizeBytes = 123456L;
        input.lastModifiedMillis = 1_716_900_000_000L;
        input.status = "processed";
        input.durationMillis = 4200L;
        record.inputs.add(input);

        RunRecord.OutputItem output = new RunRecord.OutputItem();
        output.path = "D:/projects/cohortA/FLASH/Results/Tables/Objects/3D Objects.csv";
        output.kind = "csv";
        output.sizeBytes = 9876L;
        output.fingerprint = "cafef00d";
        record.outputs.add(output);

        record.messages.add(new RunRecord.Message("warn", 1_716_912_010_000L, "one image skipped"));

        RunRecord back = RunRecordCodec.decode(RunRecordCodec.encode(record));

        assertEquals(record.runId, back.runId);
        assertEquals(record.parentRunId, back.parentRunId);
        assertEquals(record.startedAtMillis, back.startedAtMillis);
        assertEquals(record.finishedAtMillis, back.finishedAtMillis);
        assertEquals("warn", back.status);
        assertEquals("ThreeDObjectAnalysis", back.analysis);
        assertEquals(4, back.analysisIndex);
        assertEquals("3D Object Analysis", back.analysisLabel);
        assertEquals("4.0.0", back.flashVersion);
        assertEquals("1.54f", back.fijiBuild);
        assertEquals("7.3.0", back.biofVersion);
        assertEquals("abc123", back.projectFileHash);
        assertEquals("D:/projects/cohortA", back.outputRoot);

        assertEquals(Boolean.TRUE, back.parameters.get("skipExisting"));
        // parameters is a raw JSON value map; numeric boxed type (Long vs Double)
        // is a JSON-layer detail, so compare by numeric value like the rest of FLASH.
        assertEquals(8L, ((Number) back.parameters.get("parallelThreads")).longValue());
        assertEquals("fast mode", back.parameters.get("note"));

        assertEquals(1, back.inputs.size());
        RunRecord.InputItem bi = back.inputs.get(0);
        assertEquals("D:/raw/Exp1-03_LH_Hb.lif", bi.path);
        assertEquals(1, bi.seriesIndex);
        assertEquals("03", bi.animalId);
        assertEquals("LH", bi.hemisphere);
        assertEquals("Hb", bi.region);
        assertEquals("WT", bi.condition);
        assertEquals("fast", bi.fingerprintMode);
        assertEquals("deadbeef", bi.fingerprint);
        assertEquals(123456L, bi.sizeBytes);
        assertEquals(1_716_900_000_000L, bi.lastModifiedMillis);
        assertEquals("processed", bi.status);
        assertEquals(4200L, bi.durationMillis);

        assertEquals(1, back.outputs.size());
        RunRecord.OutputItem bo = back.outputs.get(0);
        assertEquals("csv", bo.kind);
        assertEquals(9876L, bo.sizeBytes);
        assertEquals("cafef00d", bo.fingerprint);

        assertEquals(1, back.messages.size());
        assertEquals("warn", back.messages.get(0).level);
        assertEquals(1_716_912_010_000L, back.messages.get(0).atMillis);
        assertEquals("one image skipped", back.messages.get(0).text);
    }

    @Test
    public void encodeIsSingleLine() {
        RunRecord record = new RunRecord();
        record.runId = "01HZX3R8K2QABCDEFGHJKMNPQR";
        record.messages.add(new RunRecord.Message("warn", 1L, "line one\nline two"));

        String json = RunRecordCodec.encode(record);

        assertFalse("Compact JSONL line must not contain a raw newline", json.contains("\n"));
        assertTrue("Embedded newline must be escaped", json.contains("\\n"));
    }

    @Test
    public void prettyHasNewlines() {
        String pretty = RunRecordCodec.encodePretty(new RunRecord());
        assertTrue(pretty.contains("\n"));
    }

    @Test
    public void extrasPreservedOnRoundTrip() throws Exception {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"runId\":\"R1\","
                + "\"futureRootKey\":{\"nested\":true},"
                + "\"inputs\":[{\"path\":\"X.lif\",\"futureInputKey\":\"keep-me\"}],"
                + "\"outputs\":[{\"path\":\"o.csv\",\"kind\":\"csv\",\"futureOutputKey\":7}]"
                + "}";

        RunRecord decoded = RunRecordCodec.decode(json);
        String encoded = RunRecordCodec.encode(decoded);
        RunRecord back = RunRecordCodec.decode(encoded);

        assertTrue(back.extras.containsKey("futureRootKey"));
        assertEquals("keep-me", back.inputs.get(0).extras.get("futureInputKey"));
        assertTrue(back.outputs.get(0).extras.containsKey("futureOutputKey"));
        assertTrue(encoded.contains("\"futureRootKey\""));
        assertTrue(encoded.contains("\"futureInputKey\""));
        assertTrue(encoded.contains("\"futureOutputKey\""));
    }

    @Test
    public void decodeRejectsWrongSchemaVersion() {
        try {
            RunRecordCodec.decode("{\"schemaVersion\":2,\"runId\":\"R\"}");
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("schemaVersion"));
            assertTrue(e.getMessage().contains("2"));
        }
    }

    @Test
    public void decodeOrNullReturnsNullOnGarbage() {
        assertNull(RunRecordCodec.decodeOrNull("{not json"));
        assertNull(RunRecordCodec.decodeOrNull(""));
        assertNull(RunRecordCodec.decodeOrNull("not even close"));
    }

    @Test
    public void missingStatusDefaultsToOk() throws Exception {
        RunRecord decoded = RunRecordCodec.decode("{\"schemaVersion\":1,\"runId\":\"R\"}");
        assertEquals("ok", decoded.status);
    }
}
