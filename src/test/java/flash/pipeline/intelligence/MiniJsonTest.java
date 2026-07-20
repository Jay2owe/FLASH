package flash.pipeline.intelligence;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MiniJsonTest {

    @Test
    public void inputCharacterAndUtf8ByteBoundsAcceptLimitAndRejectLimitPlusOne()
            throws Exception {
        String json = "\"é\"";

        assertEquals("é", MiniJson.parse(json, limits(4, 3, 4, 10, 4, 4, 8)));

        MiniJson.LimitExceededException byteFailure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parse("\"é\"", limits(3, 3, 4, 10, 4, 4, 8), "preset.json");
            }
        });
        assertLimit(byteFailure, MiniJson.LimitDimension.INPUT_UTF8_BYTES,
                "preset.json", 3, 4);

        MiniJson.LimitExceededException characterFailure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parse("\"é\"", limits(4, 2, 4, 10, 4, 4, 8));
            }
        });
        assertLimit(characterFailure, MiniJson.LimitDimension.INPUT_CHARACTERS,
                "JSON string", 2, 3);
    }

    @Test
    public void utf8EntryPointChecksLimitsBeforeDecodeAndReportsSource() throws Exception {
        final byte[] json = "\"é\"".getBytes(StandardCharsets.UTF_8);
        assertEquals("é", MiniJson.parseUtf8(json, limits(4, 3, 4, 10, 4, 4, 8)));

        MiniJson.LimitExceededException failure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parseUtf8(json, limits(3, 3, 4, 10, 4, 4, 8),
                        "catalog.json");
            }
        });
        assertLimit(failure, MiniJson.LimitDimension.INPUT_UTF8_BYTES,
                "catalog.json", 3, 4);
    }

    @Test
    public void streamEntryPointStopsAfterLimitPlusOneByte() throws Exception {
        byte[] input = repeat(' ', 100).getBytes(StandardCharsets.UTF_8);
        final ByteArrayInputStream stream = new ByteArrayInputStream(input);

        MiniJson.LimitExceededException failure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parseUtf8(stream, limits(4, 100, 4, 10, 10, 10, 10),
                        "project.json");
            }
        });

        assertLimit(failure, MiniJson.LimitDimension.INPUT_UTF8_BYTES,
                "project.json", 4, 5);
        assertEquals(95, stream.available());
    }

    @Test
    public void nestingBoundAcceptsLimitAndRejectsNextContainer() throws Exception {
        assertEquals(Arrays.asList(Arrays.asList(Arrays.asList())),
                MiniJson.parse("[[[]]]", limits(100, 100, 3, 20, 20, 20, 20)));

        MiniJson.LimitExceededException failure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parse("[[[[]]]]", limits(100, 100, 3, 20, 20, 20, 20));
            }
        });
        assertLimit(failure, MiniJson.LimitDimension.NESTING_DEPTH,
                "JSON string", 3, 4);
    }

    @Test
    public void nodeBoundCountsObjectKeysAndValuesBeforeTreeGrowth() throws Exception {
        Map<?, ?> parsed = (Map<?, ?>) MiniJson.parse("{\"k\":null}",
                limits(100, 100, 4, 3, 20, 20, 20));
        assertTrue(parsed.containsKey("k"));

        MiniJson.LimitExceededException failure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parse("{\"k\":null}", limits(100, 100, 4, 2, 20, 20, 20));
            }
        });
        assertLimit(failure, MiniJson.LimitDimension.TOTAL_NODES,
                "JSON string", 2, 3);
    }

    @Test
    public void decodedStringBoundHandlesDirectAndEscapedSupplementaryText()
            throws Exception {
        final String threeCharacters = "A😀";
        assertEquals(threeCharacters, MiniJson.parse("\"" + threeCharacters + "\"",
                limits(100, 100, 4, 10, 3, 10, 20)));
        assertEquals(threeCharacters, MiniJson.parse("\"A\\uD83D\\uDE00\"",
                limits(100, 100, 4, 10, 3, 10, 20)));

        MiniJson.LimitExceededException failure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parse("\"A😀B\"", limits(100, 100, 4, 10, 3, 10, 20));
            }
        });
        assertLimit(failure, MiniJson.LimitDimension.STRING_CHARACTERS,
                "JSON string", 3, 4);

        MiniJson.LimitExceededException keyFailure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parse("{\"abcd\":0}", limits(100, 100, 4, 10, 3, 10, 20));
            }
        });
        assertEquals(MiniJson.LimitDimension.STRING_CHARACTERS, keyFailure.getDimension());
    }

    @Test
    public void collectionBoundAppliesIndependentlyToEveryArrayAndObject() throws Exception {
        assertEquals(Arrays.asList(Long.valueOf(0), Long.valueOf(1)),
                MiniJson.parse("[0,1]", limits(100, 100, 4, 20, 20, 2, 20)));
        assertEquals(2, ((Map<?, ?>) MiniJson.parse("{\"a\":0,\"b\":1}",
                limits(100, 100, 4, 20, 20, 2, 20))).size());

        MiniJson.LimitExceededException arrayFailure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parse("[0,1,2]", limits(100, 100, 4, 20, 20, 2, 20));
            }
        });
        assertLimit(arrayFailure, MiniJson.LimitDimension.COLLECTION_ENTRIES,
                "JSON string", 2, 3);

        MiniJson.LimitExceededException objectFailure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parse("{\"a\":0,\"b\":1,\"c\":2}",
                        limits(100, 100, 4, 20, 20, 2, 20));
            }
        });
        assertEquals(MiniJson.LimitDimension.COLLECTION_ENTRIES,
                objectFailure.getDimension());
        assertEquals(3L, objectFailure.getMeasured());
    }

    @Test
    public void numericTokenBoundIsCheckedBeforeSubstringOrConversion() throws Exception {
        assertEquals(Double.valueOf(-1200.0), MiniJson.parse("-1.2e+3",
                limits(100, 100, 4, 10, 20, 10, 7)));

        MiniJson.LimitExceededException failure = expectLimit(new JsonAction() {
            @Override public void run() throws IOException {
                MiniJson.parse("-1.2e+34", limits(100, 100, 4, 10, 20, 10, 7));
            }
        });
        assertLimit(failure, MiniJson.LimitDimension.NUMBER_CHARACTERS,
                "JSON string", 7, 8);
    }

    @Test
    public void validBoundedNumbersOutsidePrimitiveRangesRemainLossless() throws Exception {
        String integer = "12345678901234567890123456789012";
        Number parsedInteger = (Number) MiniJson.parse(integer,
                limits(100, 100, 4, 10, 20, 10, 32));
        assertEquals(new BigInteger(integer), parsedInteger);
        assertEquals(integer, MiniJson.write(parsedInteger));

        Number parsedDecimal = (Number) MiniJson.parse("1e309",
                limits(100, 100, 4, 10, 20, 10, 5));
        assertEquals(new BigDecimal("1e309"), parsedDecimal);
        assertEquals("1E+309", MiniJson.write(parsedDecimal));
    }

    @Test
    public void validNearLimitUnicodeDocumentIsLosslessForStringAndUtf8Inputs()
            throws Exception {
        LinkedHashMap<String, Object> expected = new LinkedHashMap<String, Object>();
        expected.put("动物😀", "άλφα-é");
        String json = MiniJson.write(expected);
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        MiniJson.Limits exact = limits(utf8.length, json.length(), 1, 3,
                "άλφα-é".length(), 1, 32);

        assertEquals(expected, MiniJson.parse(json, exact));
        assertEquals(expected, MiniJson.parseUtf8(utf8, exact));
    }

    @Test
    public void malformedSyntaxAndSurrogatesRemainDistinctFromLimitFailures()
            throws Exception {
        assertMalformed("true false");
        assertMalformed("[1,]");
        assertMalformed("{\"a\":1,}");
        assertMalformed("\"line\nbreak\"");
        assertMalformed("01");
        assertMalformed("-");
        assertMalformed("1.");
        assertMalformed("1e");
        assertMalformed("\"\\uD800\"");
        assertMalformed("\"\\uDC00\"");
        assertMalformed("\"\\uＦＦＦＦ\"");
        assertMalformed(new String(new char[] {'"', '\ud800', '"'}));

        try {
            MiniJson.parseUtf8(new byte[] {(byte) 0xc0, (byte) 0xaf});
            fail("Expected malformed UTF-8 to be rejected.");
        } catch (MiniJson.LimitExceededException unexpected) {
            fail("Malformed UTF-8 was reported as a limit failure.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Malformed UTF-8"));
        }
    }

    @Test
    public void adversarialDepthAndNumberCasesFailAtSmallDeterministicBounds()
            throws Exception {
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 17; i++) nested.append('[');
        for (int i = 0; i < 17; i++) nested.append(']');
        final String deepJson = nested.toString();
        assertEquals(MiniJson.LimitDimension.NESTING_DEPTH,
                expectLimit(new JsonAction() {
                    @Override public void run() throws IOException {
                        MiniJson.parse(deepJson, limits(100, 100, 16, 100, 20, 20, 20));
                    }
                }).getDimension());

        final String longNumber = repeat('7', 33);
        assertEquals(MiniJson.LimitDimension.NUMBER_CHARACTERS,
                expectLimit(new JsonAction() {
                    @Override public void run() throws IOException {
                        MiniJson.parse(longNumber, limits(100, 100, 4, 10, 20, 10, 32));
                    }
                }).getDimension());
    }

    @Test
    public void invalidLimitsAreRejectedWithoutParsing() {
        try {
            limits(-1, 1, 1, 1, 1, 1, 1);
            fail("Expected a negative limit to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxUtf8Bytes"));
        }
    }

    private static MiniJson.Limits limits(long bytes,
                                          int characters,
                                          int depth,
                                          long nodes,
                                          int stringCharacters,
                                          int collectionSize,
                                          int numberCharacters) {
        return new MiniJson.Limits(bytes, characters, depth, nodes,
                stringCharacters, collectionSize, numberCharacters);
    }

    private static MiniJson.LimitExceededException expectLimit(JsonAction action)
            throws Exception {
        try {
            action.run();
            fail("Expected a JSON resource limit failure.");
            return null;
        } catch (MiniJson.LimitExceededException expected) {
            return expected;
        }
    }

    private static void assertLimit(MiniJson.LimitExceededException failure,
                                    MiniJson.LimitDimension dimension,
                                    String source,
                                    long limit,
                                    long measured) {
        assertEquals(dimension, failure.getDimension());
        assertEquals(source, failure.getSource());
        assertEquals(limit, failure.getLimit());
        assertEquals(measured, failure.getMeasured());
        assertTrue(failure.getMessage().contains(dimension.name()));
    }

    private static void assertMalformed(String json) throws Exception {
        try {
            MiniJson.parse(json);
            fail("Expected malformed JSON to be rejected: " + json);
        } catch (MiniJson.LimitExceededException unexpected) {
            fail("Malformed JSON was reported as a limit failure: " + json);
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static String repeat(char ch, int count) {
        char[] value = new char[count];
        Arrays.fill(value, ch);
        return new String(value);
    }

    private interface JsonAction {
        void run() throws IOException;
    }
}
