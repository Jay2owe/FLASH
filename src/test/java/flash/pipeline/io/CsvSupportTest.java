package flash.pipeline.io;

import flash.pipeline.testutil.TestWait;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CsvSupportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void joinRowAndParseRecord_roundTripCommaQuoteTrailingEmptyAndWhitespace() throws Exception {
        List<String> row = Arrays.asList(
                "plain",
                "with,comma",
                "He said \"hi\"",
                "",
                " tail ");

        String joined = CsvSupport.joinRow(row);

        assertArrayEquals(row.toArray(new String[0]), CsvSupport.parseRecord(joined));
    }

    @Test
    public void recordReader_roundTripsQuotedMultilineField() throws Exception {
        File csv = temp.newFile("multiline.csv");
        PrintWriter pw = CsvSupport.newWriter(csv);
        try {
            pw.println(CsvSupport.joinRow(Arrays.asList("A", "B")));
            pw.println(CsvSupport.joinRow(Arrays.asList("1", "two\nlines")));
        } finally {
            pw.close();
        }

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csv);
        try {
            CsvSupport.Record header = reader.readRecord();
            CsvSupport.Record row = reader.readRecord();
            assertArrayEquals(new String[]{"A", "B"}, CsvSupport.parseRecord(header.text));
            assertArrayEquals(new String[]{"1", "two\nlines"}, CsvSupport.parseRecord(row.text));
        } finally {
            reader.close();
        }
    }

    @Test
    public void recordReader_preservesEmbeddedCrLfCrLfAndQuotesWithinBudgets()
            throws Exception {
        File csv = temp.newFile("multiline-dialects.csv");
        String field = "alpha\r\nbeta\rgamma\ndelta \"quoted\"";
        String contents = "A,B\r\n1,\"alpha\r\nbeta\rgamma\ndelta \"\"quoted\"\"\"\r\n";
        byte[] bytes = contents.getBytes(CsvSupport.CHARSET);
        Files.write(csv.toPath(), bytes);

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csv,
                new CsvSupport.ReadLimits(bytes.length, 2L, 128));
        try {
            assertArrayEquals(new String[]{"A", "B"},
                    CsvSupport.parseRecord(reader.readRecord().text));
            assertArrayEquals(new String[]{"1", field},
                    CsvSupport.parseRecord(reader.readRecord().text));
            assertEquals(2L, reader.getRecordsRead());
            assertTrue(reader.getBytesRead() <= bytes.length);
            assertTrue(reader.getMaximumRecordCharactersObserved() < 128);
            assertEquals(null, reader.readRecord());
        } finally {
            reader.close();
        }
    }

    @Test
    public void recordReader_rejectsByteBudgetBeforeAllocatingARecord() throws Exception {
        File csv = temp.newFile("byte-budget.csv");
        byte[] bytes = "A,B\n1,23456789\n".getBytes(CsvSupport.CHARSET);
        Files.write(csv.toPath(), bytes);

        try {
            CsvSupport.openRecordReader(csv,
                    new CsvSupport.ReadLimits(bytes.length - 1L, 10L, 128));
            fail("Expected explicit byte budget to reject the file");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("byte limit"));
            assertTrue(expected.getMessage().contains(csv.getAbsolutePath()));
        }
    }

    @Test
    public void recordReader_rejectsLogicalRecordBudgetBeforeBuildingAnotherRecord()
            throws Exception {
        File csv = temp.newFile("record-budget.csv");
        Files.write(csv.toPath(), "A\n1\n2\n".getBytes(CsvSupport.CHARSET));

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csv,
                new CsvSupport.ReadLimits(64L, 2L, 8));
        try {
            assertEquals("A", reader.readRecord().text);
            assertEquals("1", reader.readRecord().text);
            try {
                reader.readRecord();
                fail("Expected logical-record budget to reject the third record");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("logical-record limit"));
            }
            assertEquals(2L, reader.getRecordsRead());
            assertEquals(1, reader.getMaximumRecordCharactersObserved());
        } finally {
            reader.close();
        }
    }

    @Test
    public void recordReader_acceptsLongMaxByteBudgetWithoutOverflow() throws Exception {
        File csv = temp.newFile("long-max-budget.csv");
        Files.write(csv.toPath(), "A\n1\n".getBytes(CsvSupport.CHARSET));

        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(csv,
                new CsvSupport.ReadLimits(Long.MAX_VALUE, 2L, 8));
        try {
            assertEquals("A", reader.readRecord().text);
            assertEquals("1", reader.readRecord().text);
            assertEquals(null, reader.readRecord());
        } finally {
            reader.close();
        }
    }

    @Test
    public void joinRow_serializesEverySpreadsheetPrefixAndUnicodeExactly() throws Exception {
        String joined = CsvSupport.joinRow(Arrays.asList(
                "=cmd",
                "+cmd",
                "-cmd",
                "@cmd",
                "\tcmd",
                "\rcmd",
                "\ncmd",
                "  =after-space",
                "\u0000+after-control",
                "\u00a0-after-nbsp",
                "\u200b@after-format-control",
                "-12.5",
                "safe",
                "comma,\u96ea",
                "quote \"\u03a9\""));

        String expected = "'=cmd,'+cmd,'-cmd,'@cmd,'\tcmd,\"'\rcmd\",\"'\ncmd\","
                + "'  =after-space,'\u0000+after-control,'\u00a0-after-nbsp,"
                + "'\u200b@after-format-control,'-12.5,safe,"
                + "\"comma,\u96ea\",\"quote \"\"\u03a9\"\"\"";
        assertEquals(expected, joined);
        assertArrayEquals(expected.getBytes(CsvSupport.CHARSET),
                joined.getBytes(CsvSupport.CHARSET));
    }

    @Test
    public void spreadsheetSafeFieldValue_leavesBenignLeadingWhitespaceUntouched() {
        assertEquals("  safe", CsvSupport.spreadsheetSafeFieldValue("  safe"));
        assertEquals("\u0000safe", CsvSupport.spreadsheetSafeFieldValue("\u0000safe"));
        assertEquals("\u96ea\u03a9", CsvSupport.spreadsheetSafeFieldValue("\u96ea\u03a9"));
    }

    @Test
    public void escapeField_scansSupplementaryFormatControlsByCodePoint() {
        String serialized = CsvSupport.escapeField("\udb40\udc01=after-language-tag");
        byte[] expectedUtf8 = new byte[]{
                0x27,
                (byte) 0xf3, (byte) 0xa0, (byte) 0x80, (byte) 0x81,
                0x3d, 0x61, 0x66, 0x74, 0x65, 0x72, 0x2d,
                0x6c, 0x61, 0x6e, 0x67, 0x75, 0x61, 0x67, 0x65,
                0x2d, 0x74, 0x61, 0x67
        };

        assertArrayEquals(expectedUtf8, serialized.getBytes(CsvSupport.CHARSET));
    }

    @Test
    public void parseRecord_rejectsMalformedQuoteSequences() {
        try {
            CsvSupport.parseRecord("\"bad\"quote");
            throw new AssertionError("Expected malformed CSV to throw");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Unexpected character"));
        }
    }

    @Test
    public void writeAtomicallyReplacesFinalFileAndRemovesTemp() throws Exception {
        File csv = temp.newFile("atomic.csv");
        Files.write(csv.toPath(), Arrays.asList("old"), CsvSupport.CHARSET);

        CsvSupport.writeAtomically(csv, new CsvSupport.WriterAction() {
            @Override
            public void write(PrintWriter writer) {
                writer.println("new");
            }
        });

        assertEquals(Arrays.asList("new"), Files.readAllLines(csv.toPath(), CsvSupport.CHARSET));
        assertNoLeftoverTemp(csv);
    }

    @Test
    public void partialWriteFaultNamesTriggerAndPreservesPriorGeneration() throws Exception {
        File csv = temp.newFile("partial.csv");
        byte[] sentinel = "sentinel-generation\n".getBytes(CsvSupport.CHARSET);
        Files.write(csv.toPath(), sentinel);

        try {
            CsvSupport.writeAtomically(csv,
                    FileOperationFaults.partialWriteThenFail(csv, "replacement-generation\n", 7));
            fail("Expected deterministic partial-write fault");
        } catch (FileOperationFaults.InjectedFailure expected) {
            assertEquals(FileOperationFaults.Trigger.PARTIAL_WRITE, expected.getTrigger());
            assertEquals(csv.toPath().toAbsolutePath().normalize(), expected.getAffectedPath());
            assertTrue(expected.getMessage().contains("partial-write"));
            assertTrue(expected.getMessage().contains(csv.getAbsolutePath()));
        }

        assertArrayEquals("A failed partial write must not replace the last good generation",
                sentinel, Files.readAllBytes(csv.toPath()));
        assertNoLeftoverTemp(csv);
    }

    @Test
    public void validationFaultPreservesEveryPriorByteAndRemovesSiblingTemp() throws Exception {
        File csv = temp.newFile("validation-fault.csv");
        final byte[] prior = "A,B\r\nold,\"last\r\ngood\"\r\n".getBytes(CsvSupport.CHARSET);
        Files.write(csv.toPath(), prior);
        final CsvSupport.PublicationOperations defaults =
                CsvSupport.defaultPublicationOperations();
        CsvSupport.setPublicationOperationsForTest(new CsvSupport.PublicationOperations() {
            @Override
            public PrintWriter openWriter(File file) throws IOException {
                return defaults.openWriter(file);
            }

            @Override
            public void validate(File file) throws IOException {
                throw new IOException("injected staged validation failure");
            }

            @Override
            public void replace(Path source, Path target) throws IOException {
                defaults.replace(source, target);
            }
        });
        try {
            try {
                CsvSupport.writeAtomically(csv, new CsvSupport.WriterAction() {
                    @Override
                    public void write(PrintWriter writer) {
                        writer.println("A,B");
                        writer.println("new,8");
                    }
                });
                fail("Expected staged validation failure");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("validation failure"));
            }
        } finally {
            CsvSupport.resetPublicationOperationsForTest();
        }

        assertArrayEquals(prior, Files.readAllBytes(csv.toPath()));
        assertNoLeftoverTemp(csv);
    }

    @Test
    public void replaceFaultPreservesEveryPriorByteAndRemovesSiblingTemp() throws Exception {
        File csv = temp.newFile("replace-fault.csv");
        final byte[] prior = "A,B\r\nold,\"last\r\ngood\"\r\n".getBytes(CsvSupport.CHARSET);
        Files.write(csv.toPath(), prior);
        final CsvSupport.PublicationOperations defaults =
                CsvSupport.defaultPublicationOperations();
        CsvSupport.setPublicationOperationsForTest(new CsvSupport.PublicationOperations() {
            @Override
            public PrintWriter openWriter(File file) throws IOException {
                return defaults.openWriter(file);
            }

            @Override
            public void validate(File file) throws IOException {
                defaults.validate(file);
            }

            @Override
            public void replace(Path source, Path target) throws IOException {
                throw new IOException("injected atomic replacement failure");
            }
        });
        try {
            try {
                CsvSupport.writeAtomically(csv, new CsvSupport.WriterAction() {
                    @Override
                    public void write(PrintWriter writer) {
                        writer.println("A,B");
                        writer.println("new,9");
                    }
                });
                fail("Expected atomic replacement failure");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("replacement failure"));
            }
        } finally {
            CsvSupport.resetPublicationOperationsForTest();
        }

        assertArrayEquals(prior, Files.readAllBytes(csv.toPath()));
        assertNoLeftoverTemp(csv);
    }

    @Test
    public void atomicMoveRejectionIsBoundedAndPreservesPriorGeneration() throws Exception {
        File csv = temp.newFile("move-rejected.csv");
        Files.write(csv.toPath(), "sentinel-generation\n".getBytes(CsvSupport.CHARSET));

        FileOperationFaults.FailureObservation observation =
                FileOperationFaults.failSinglePublication(
                        csv,
                        "replacement-generation\n".getBytes(CsvSupport.CHARSET),
                        FileOperationFaults.Trigger.ATOMIC_MOVE_REJECTED);

        assertEquals(FileOperationFaults.Trigger.ATOMIC_MOVE_REJECTED,
                observation.getTrigger());
        assertEquals(csv.toPath().toAbsolutePath().normalize(),
                observation.getAffectedPath());
        assertTrue(observation.getFailureMessage().contains("atomic-move-rejected"));
        assertArrayEquals(observation.getPriorGeneration(0),
                observation.getCurrentGeneration(0));
        assertArrayEquals(observation.getPriorGeneration(0), Files.readAllBytes(csv.toPath()));
        assertTrue("Fault fixture staging files must be cleaned", observation.isStagingClean());
        assertFalse("Injected rejection must not create a backup generation",
                new File(csv.getParentFile(), csv.getName() + ".bak").exists());
    }

    private static void assertNoLeftoverTemp(File csv) {
        File[] leftovers = csv.getAbsoluteFile().getParentFile().listFiles((dir, name) ->
                name.startsWith("." + csv.getName() + ".") && name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    /**
     * Shared deterministic publication faults used by the owning file-operation
     * test suites. The fixture writes only sibling staging files and never
     * touches a target generation once a fault has been selected.
     */
    public static final class FileOperationFaults {
        private static final long CLEANUP_TIMEOUT_MILLIS = 1000L;

        private FileOperationFaults() {
        }

        public enum Trigger {
            ATOMIC_MOVE_REJECTED("atomic-move-rejected"),
            PARTIAL_WRITE("partial-write"),
            ZERO_OUTPUT("zero-output"),
            TRUNCATED_OUTPUT("truncated-output"),
            REOPEN_VALIDATION("reopen-validation");

            private final String label;

            Trigger(String label) {
                this.label = label;
            }

            public String getLabel() {
                return label;
            }
        }

        public static final class InjectedFailure extends IOException {
            private final Trigger trigger;
            private final Path affectedPath;

            private InjectedFailure(Trigger trigger, Path affectedPath, String detail) {
                super(message(trigger, affectedPath, detail));
                this.trigger = trigger;
                this.affectedPath = normalize(affectedPath);
            }

            private InjectedFailure(Trigger trigger, Path affectedPath, String detail,
                                    Throwable cause) {
                super(message(trigger, affectedPath, detail), cause);
                this.trigger = trigger;
                this.affectedPath = normalize(affectedPath);
            }

            public Trigger getTrigger() {
                return trigger;
            }

            public Path getAffectedPath() {
                return affectedPath;
            }

            private static String message(Trigger trigger, Path affectedPath, String detail) {
                return "Injected " + trigger.getLabel() + " fault for "
                        + normalize(affectedPath) + ": " + detail;
            }
        }

        public static final class FailureObservation {
            private final InjectedFailure failure;
            private final List<byte[]> priorGenerations;
            private final List<byte[]> currentGenerations;
            private final List<Path> stagingPaths;

            private FailureObservation(InjectedFailure failure,
                                       List<byte[]> priorGenerations,
                                       List<byte[]> currentGenerations,
                                       List<Path> stagingPaths) {
                this.failure = failure;
                this.priorGenerations = copyBytes(priorGenerations);
                this.currentGenerations = copyBytes(currentGenerations);
                this.stagingPaths = Collections.unmodifiableList(
                        new ArrayList<Path>(stagingPaths));
            }

            public Trigger getTrigger() {
                return failure.getTrigger();
            }

            public Path getAffectedPath() {
                return failure.getAffectedPath();
            }

            public String getFailureMessage() {
                return failure.getMessage();
            }

            public byte[] getPriorGeneration(int index) {
                return priorGenerations.get(index).clone();
            }

            public byte[] getCurrentGeneration(int index) {
                return currentGenerations.get(index).clone();
            }

            public int getTargetCount() {
                return priorGenerations.size();
            }

            public boolean isStagingClean() {
                for (Path path : stagingPaths) {
                    if (Files.exists(path)) {
                        return false;
                    }
                }
                return true;
            }
        }

        public static CsvSupport.WriterAction partialWriteThenFail(
                final File target, final String candidate, final int charactersBeforeFailure) {
            if (target == null) {
                throw new IllegalArgumentException("target must not be null");
            }
            if (candidate == null || charactersBeforeFailure <= 0
                    || charactersBeforeFailure >= candidate.length()) {
                throw new IllegalArgumentException(
                        "partial write must stop strictly inside a non-empty candidate");
            }
            return new CsvSupport.WriterAction() {
                @Override
                public void write(PrintWriter writer) throws IOException {
                    writer.write(candidate, 0, charactersBeforeFailure);
                    writer.flush();
                    throw new InjectedFailure(Trigger.PARTIAL_WRITE, target.toPath(),
                            "wrote " + charactersBeforeFailure + " of "
                                    + candidate.length() + " characters to the staging file");
                }
            };
        }

        public static FailureObservation failSinglePublication(
                File target, byte[] candidate, Trigger trigger) throws Exception {
            return failBatchPublication(
                    Collections.singletonList(target),
                    Collections.singletonList(candidate),
                    0,
                    trigger);
        }

        public static FailureObservation failBatchPublication(
                List<File> targets, List<byte[]> candidates, int failureIndex,
                Trigger trigger) throws Exception {
            validatePlan(targets, candidates, failureIndex, trigger);

            List<byte[]> prior = readTargets(targets);
            final List<Path> staging = createStagingFiles(targets);
            InjectedFailure observed = null;
            try {
                for (int i = 0; i < targets.size(); i++) {
                    if (i == failureIndex && trigger != Trigger.ATOMIC_MOVE_REJECTED) {
                        writeFaultyCandidate(staging.get(i), targets.get(i).toPath(),
                                candidates.get(i), trigger);
                    } else {
                        Files.write(staging.get(i), candidates.get(i));
                        verifyExactReopen(staging.get(i), targets.get(i).toPath(),
                                candidates.get(i), null);
                    }
                }

                if (trigger == Trigger.ATOMIC_MOVE_REJECTED) {
                    rejectMove(staging.get(failureIndex), targets.get(failureIndex).toPath());
                }
                throw new AssertionError("Fault plan completed without injecting "
                        + trigger.getLabel());
            } catch (InjectedFailure expected) {
                observed = expected;
            } finally {
                for (Path path : staging) {
                    Files.deleteIfExists(path);
                }
            }

            final InjectedFailure failure = observed;
            if (failure == null) {
                throw new AssertionError("Expected an injected file-operation failure");
            }
            TestWait.await("fault staging cleanup for " + failure.getAffectedPath(),
                    CLEANUP_TIMEOUT_MILLIS, new TestWait.Condition() {
                        @Override
                        public boolean isMet() {
                            for (Path path : staging) {
                                if (Files.exists(path)) {
                                    return false;
                                }
                            }
                            return true;
                        }
                    });
            return new FailureObservation(failure, prior, readTargets(targets), staging);
        }

        private static void writeFaultyCandidate(Path staging, Path target,
                                                 byte[] candidate, Trigger trigger)
                throws IOException {
            switch (trigger) {
                case PARTIAL_WRITE:
                    int partialLength = Math.max(1, candidate.length / 2);
                    partialLength = Math.min(partialLength, Math.max(0, candidate.length - 1));
                    Files.write(staging, Arrays.copyOf(candidate, partialLength));
                    throw new InjectedFailure(trigger, target,
                            "wrote " + partialLength + " of " + candidate.length
                                    + " bytes to the staging file");
                case ZERO_OUTPUT:
                    Files.write(staging, new byte[0]);
                    verifyExactReopen(staging, target, candidate, trigger);
                    break;
                case TRUNCATED_OUTPUT:
                    int truncatedLength = Math.max(0, candidate.length - 1);
                    Files.write(staging, Arrays.copyOf(candidate, truncatedLength));
                    verifyExactReopen(staging, target, candidate, trigger);
                    break;
                case REOPEN_VALIDATION:
                    Files.write(staging, candidate);
                    verifyExactReopen(staging, target, candidate, null);
                    throw new InjectedFailure(trigger, target,
                            "reopen validator rejected the staged artifact");
                default:
                    throw new IllegalArgumentException("Unsupported staging fault: " + trigger);
            }
            throw new AssertionError("Faulty candidate unexpectedly passed validation");
        }

        private static void verifyExactReopen(Path staging, Path target, byte[] expected,
                                              Trigger mismatchTrigger) throws IOException {
            byte[] reopened = Files.readAllBytes(staging);
            if (!Arrays.equals(expected, reopened)) {
                Trigger trigger = mismatchTrigger == null
                        ? Trigger.REOPEN_VALIDATION : mismatchTrigger;
                throw new InjectedFailure(trigger, target,
                        "reopened " + reopened.length + " bytes; expected "
                                + expected.length);
            }
        }

        private static void rejectMove(final Path staging, final Path target)
                throws IOException {
            try {
                IoUtils.moveReplacing(staging, target, new IoUtils.FileMover() {
                    private int attempts;

                    @Override
                    public void move(Path source, Path destination, CopyOption... options)
                            throws IOException {
                        attempts++;
                        if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                            throw new AtomicMoveNotSupportedException(
                                    source.toString(), destination.toString(),
                                    "injected atomic move rejection");
                        }
                        throw new IOException("injected replacement rejection attempt " + attempts);
                    }
                }, new IoUtils.Sleeper() {
                    @Override
                    public void sleep(long millis) {
                        // Deliberately no wall-clock wait in fault tests.
                    }
                });
            } catch (IOException expected) {
                throw new InjectedFailure(Trigger.ATOMIC_MOVE_REJECTED, target,
                        "atomic and replacement moves were rejected", expected);
            }
            throw new AssertionError("Injected move rejection unexpectedly committed");
        }

        private static void validatePlan(List<File> targets, List<byte[]> candidates,
                                         int failureIndex, Trigger trigger) throws IOException {
            if (targets == null || candidates == null || targets.isEmpty()
                    || targets.size() != candidates.size()) {
                throw new IllegalArgumentException(
                        "targets and candidates must be non-empty and have equal size");
            }
            if (failureIndex < 0 || failureIndex >= targets.size()) {
                throw new IllegalArgumentException("failureIndex is outside the publication batch");
            }
            if (trigger == null) {
                throw new IllegalArgumentException("trigger must not be null");
            }
            for (int i = 0; i < targets.size(); i++) {
                File target = targets.get(i);
                if (target == null || !target.isFile()) {
                    throw new IOException("sentinel generation is missing for target " + i);
                }
                if (candidates.get(i) == null || candidates.get(i).length < 2) {
                    throw new IllegalArgumentException(
                            "candidate " + i + " must contain at least two bytes");
                }
            }
        }

        private static List<Path> createStagingFiles(List<File> targets) throws IOException {
            List<Path> staging = new ArrayList<Path>();
            try {
                for (File target : targets) {
                    Path parent = target.toPath().toAbsolutePath().normalize().getParent();
                    staging.add(Files.createTempFile(parent,
                            "." + target.getName() + ".fault.", ".tmp"));
                }
                return staging;
            } catch (IOException failure) {
                for (Path path : staging) {
                    Files.deleteIfExists(path);
                }
                throw failure;
            }
        }

        private static List<byte[]> readTargets(List<File> targets) throws IOException {
            List<byte[]> generations = new ArrayList<byte[]>();
            for (File target : targets) {
                generations.add(Files.readAllBytes(target.toPath()));
            }
            return generations;
        }

        private static List<byte[]> copyBytes(List<byte[]> values) {
            List<byte[]> copies = new ArrayList<byte[]>();
            for (byte[] value : values) {
                copies.add(value.clone());
            }
            return Collections.unmodifiableList(copies);
        }

        private static Path normalize(Path path) {
            return path.toAbsolutePath().normalize();
        }
    }
}
