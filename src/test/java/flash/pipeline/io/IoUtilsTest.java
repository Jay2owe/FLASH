package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IoUtilsTest {

    private static final IoUtils.Sleeper NO_SLEEP = new IoUtils.Sleeper() {
        @Override
        public void sleep(long millis) {
            // Deliberately avoid wall-clock waits in filesystem fault tests.
        }
    };

    private static final IoUtils.FaultInjector NO_FAULTS = new IoUtils.FaultInjector() {
        @Override
        public void checkpoint(IoUtils.FaultPoint point, Path path) {
        }
    };

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void moveReplacing_fallsBackAndRetriesWhenAtomicMoveFails() throws Exception {
        Path source = write("START_HERE.html.tmp", bytes("new"));
        Path target = write("START_HERE.html", bytes("old"));

        final List<List<CopyOption>> moveOptions = new ArrayList<List<CopyOption>>();
        final List<Long> sleeps = new ArrayList<Long>();
        final int[] calls = new int[] {0};

        IoUtils.moveReplacing(source, target, new IoUtils.FileMover() {
            @Override
            public void move(Path from, Path to, CopyOption... options) throws IOException {
                calls[0]++;
                moveOptions.add(Arrays.asList(options));
                if (calls[0] == 1) {
                    throw new IOException("sync rejected atomic move");
                }
                if (calls[0] == 2) {
                    throw new IOException("target busy");
                }
                Files.move(from, to, options);
            }
        }, new IoUtils.Sleeper() {
            @Override
            public void sleep(long millis) {
                sleeps.add(Long.valueOf(millis));
            }
        });

        assertArrayEquals(bytes("new"), Files.readAllBytes(target));
        assertFalse(Files.exists(source));
        assertTrue(moveOptions.get(0).contains(StandardCopyOption.ATOMIC_MOVE));
        assertFalse(moveOptions.get(1).contains(StandardCopyOption.ATOMIC_MOVE));
        assertEquals(Arrays.asList(Long.valueOf(100L)), sleeps);
        assertNoTransactionArtifacts();
    }

    @Test
    public void commitReplacingSmallFile_repeatedCallsPublishExactBinaryBytes() throws Exception {
        Path target = new File(temp.getRoot(), "config.json").toPath();
        byte[][] generations = new byte[][] {
                new byte[] {0, 1, 2, 3, -1},
                new byte[] {9, 0, 8, 0, 7},
                bytes("third generation")
        };

        for (int index = 0; index < generations.length; index++) {
            Path source = write("config-" + index + ".tmp", generations[index]);
            IoUtils.commitReplacingSmallFile(source, target);
            assertArrayEquals(generations[index], Files.readAllBytes(target));
            assertFalse("caller temp should be removed", Files.exists(source));
            assertNoTransactionArtifacts();
        }
    }

    @Test
    public void commitReplacingSmallFile_windowsRenameLockUsesVerifiedInPlaceFallback()
            throws Exception {
        Path source = write("locked-config.tmp", bytes("recovered"));
        Path target = write("locked config μ.json", bytes("old"));
        final int[] moveCalls = new int[] {0};

        IoUtils.commitReplacingSmallFile(source, target, new IoUtils.FileMover() {
            @Override
            public void move(Path from, Path to, CopyOption... options) throws IOException {
                moveCalls[0]++;
                throw new java.nio.file.AccessDeniedException(from + " -> " + to);
            }
        }, NO_SLEEP);

        assertArrayEquals(bytes("recovered"), Files.readAllBytes(target));
        assertFalse(Files.exists(source));
        assertTrue("move should be retried before the in-place fallback", moveCalls[0] >= 2);
        assertNoTransactionArtifacts();
    }

    @Test
    public void failureBeforeReplacementPreservesExactPriorGeneration() throws Exception {
        Path source = write("before.tmp", bytes("candidate"));
        Path target = write("before.json", new byte[] {5, 0, 4, 0, 3});
        final byte[] old = Files.readAllBytes(target);

        try {
            IoUtils.commitReplacingSmallFile(source, target, realMover(), NO_SLEEP,
                    failAlways(IoUtils.FaultPoint.BEFORE_REPLACEMENT, "before publication"));
            fail("expected injected pre-publication failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("before publication"));
        }

        assertArrayEquals(old, Files.readAllBytes(target));
        assertFalse(Files.exists(source));
        assertNoTransactionArtifacts();
    }

    @Test
    public void moveFailureAfterTargetMutationRollsBackExactPriorBytes() throws Exception {
        final Path source = write("partial.tmp", bytes("complete candidate"));
        final Path target = write("partial.bin", new byte[] {7, 6, 0, 5, 4, 3});
        final byte[] old = Files.readAllBytes(target);

        try {
            IoUtils.moveReplacing(source, target, new IoUtils.FileMover() {
                @Override
                public void move(Path from, Path to, CopyOption... options) throws IOException {
                    Files.write(to, new byte[] {99, 0});
                    throw new IOException("provider failed after truncating target");
                }
            }, NO_SLEEP);
            fail("expected replacement failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Could not replace"));
        }

        assertArrayEquals(old, Files.readAllBytes(target));
        assertArrayEquals(bytes("complete candidate"), Files.readAllBytes(source));
        assertNoTransactionArtifacts();
    }

    @Test
    public void providerThrowAfterCompleteReplacementIsAcceptedOnlyAfterValidation()
            throws Exception {
        Path source = write("after.tmp", new byte[] {1, 0, 2, 0, 3});
        Path target = write("after.bin", bytes("old"));
        final int[] calls = new int[] {0};

        IoUtils.moveReplacing(source, target, new IoUtils.FileMover() {
            @Override
            public void move(Path from, Path to, CopyOption... options) throws IOException {
                calls[0]++;
                Files.move(from, to, options);
                throw new IOException("provider reported failure after completed move");
            }
        }, NO_SLEEP);

        assertEquals(1, calls[0]);
        assertArrayEquals(new byte[] {1, 0, 2, 0, 3}, Files.readAllBytes(target));
        assertFalse(Files.exists(source));
        assertNoTransactionArtifacts();
    }

    @Test
    public void validationFailureAfterReplacementRollsBackPriorGeneration() throws Exception {
        Path source = write("validation.tmp", bytes("candidate"));
        Path target = write("validation.json", bytes("old generation"));
        final byte[] old = Files.readAllBytes(target);

        try {
            IoUtils.commitReplacingSmallFile(source, target, realMover(), NO_SLEEP,
                    failAlways(IoUtils.FaultPoint.BEFORE_VALIDATION,
                            "injected reopen validation failure"));
            fail("expected validation failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("validation"));
        }

        assertArrayEquals(old, Files.readAllBytes(target));
        assertFalse(Files.exists(source));
        assertNoTransactionArtifacts();
    }

    @Test
    public void smallFileFailureAfterMutatingInPlaceFallbackRollsBackPriorGeneration()
            throws Exception {
        Path source = write("in-place.tmp", bytes("new exact bytes"));
        Path target = write("in-place.json", bytes("old exact bytes"));
        final byte[] old = Files.readAllBytes(target);
        final int[] afterReplacement = new int[] {0};

        IoUtils.FaultInjector failAfterInPlace = new IoUtils.FaultInjector() {
            @Override
            public void checkpoint(IoUtils.FaultPoint point, Path path) throws IOException {
                if (point == IoUtils.FaultPoint.AFTER_REPLACEMENT
                        && ++afterReplacement[0] == 7) {
                    throw new IOException("failure after in-place target mutation");
                }
            }
        };
        try {
            IoUtils.commitReplacingSmallFile(source, target, mutatingRejectedMover(), NO_SLEEP,
                    failAfterInPlace);
            fail("expected post-mutation failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("in-place target mutation"));
        }

        assertArrayEquals(old, Files.readAllBytes(target));
        assertFalse(Files.exists(source));
        assertNoTransactionArtifacts();
    }

    @Test
    public void transientRollbackFaultIsRetriedAndExactPriorBytesWin() throws Exception {
        Path source = write("rollback.tmp", bytes("candidate"));
        Path target = write("rollback.bin", new byte[] {3, 2, 1, 0});
        final byte[] old = Files.readAllBytes(target);
        final int[] rollbackCalls = new int[] {0};
        IoUtils.FaultInjector faults = new IoUtils.FaultInjector() {
            @Override
            public void checkpoint(IoUtils.FaultPoint point, Path path) throws IOException {
                if (point == IoUtils.FaultPoint.BEFORE_ROLLBACK
                        && rollbackCalls[0]++ == 0) {
                    throw new IOException("transient rollback lock");
                }
            }
        };

        try {
            IoUtils.moveReplacing(source, target, mutatingRejectedMover(), NO_SLEEP, faults);
            fail("expected replacement failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Could not replace"));
        }

        assertTrue("rollback should have retried", rollbackCalls[0] >= 2);
        assertArrayEquals(old, Files.readAllBytes(target));
        assertTrue(Files.exists(source));
        assertNoTransactionArtifacts();
    }

    @Test
    public void persistentRollbackFailureRetainsVerifiedPriorAndCandidateWithExactDiagnostics()
            throws Exception {
        Path source = write("persistent.tmp", bytes("candidate generation"));
        Path target = write("persistent.bin", bytes("prior generation"));
        final byte[] prior = Files.readAllBytes(target);
        final byte[] candidate = Files.readAllBytes(source);
        IOException thrown = null;

        try {
            IoUtils.moveReplacing(source, target, mutatingRejectedMover(), NO_SLEEP,
                    failAlways(IoUtils.FaultPoint.BEFORE_ROLLBACK,
                            "persistent rollback rejection"));
            fail("expected replacement and rollback failure");
        } catch (IOException expected) {
            thrown = expected;
        }

        Path backup = findTransactionArtifact(".backup");
        Path stagedCandidate = findTransactionArtifact(".candidate");
        assertArrayEquals(prior, Files.readAllBytes(backup));
        assertArrayEquals(candidate, Files.readAllBytes(stagedCandidate));
        assertArrayEquals(candidate, Files.readAllBytes(source));
        String diagnostic = throwableDiagnostic(thrown);
        assertTrue(diagnostic.contains(backup.toAbsolutePath().normalize().toString()));
        assertTrue(diagnostic.contains(stagedCandidate.toAbsolutePath().normalize().toString()));
        assertTrue(diagnostic.contains("sha256=" + sha256(prior)));
        assertTrue(diagnostic.contains("sha256=" + sha256(candidate)));
    }

    @Test
    public void smallFileRollbackFailureNeverDeletesEveryCandidateCopy() throws Exception {
        Path source = write("small-persistent.tmp", bytes("recover this candidate"));
        Path target = write("small-persistent.json", bytes("recover this prior"));
        final int[] replacements = new int[] {0};
        IoUtils.FaultInjector faults = new IoUtils.FaultInjector() {
            @Override
            public void checkpoint(IoUtils.FaultPoint point, Path path) throws IOException {
                if (point == IoUtils.FaultPoint.AFTER_REPLACEMENT
                        && ++replacements[0] == 7) {
                    throw new IOException("failure after in-place target mutation");
                }
                if (point == IoUtils.FaultPoint.BEFORE_ROLLBACK) {
                    throw new IOException("persistent rollback rejection");
                }
            }
        };

        try {
            IoUtils.commitReplacingSmallFile(source, target, mutatingRejectedMover(), NO_SLEEP,
                    faults);
            fail("expected replacement and rollback failure");
        } catch (IOException expected) {
            assertTrue(throwableDiagnostic(expected).contains("Recovery artifacts retained"));
        }

        assertArrayEquals(bytes("recover this candidate"), Files.readAllBytes(source));
        assertArrayEquals(bytes("recover this candidate"),
                Files.readAllBytes(findTransactionArtifact(".candidate")));
        assertArrayEquals(bytes("recover this prior"),
                Files.readAllBytes(findTransactionArtifact(".backup")));
    }

    @Test
    public void pendingInterruptSkipsRollbackSleepsButCompletesTransientRestore()
            throws Exception {
        Path source = write("interrupted.tmp", bytes("candidate"));
        Path target = write("interrupted.bin", bytes("prior"));
        final int[] rollbackCalls = new int[] {0};
        final int[] sleeps = new int[] {0};
        IoUtils.FaultInjector faults = new IoUtils.FaultInjector() {
            @Override
            public void checkpoint(IoUtils.FaultPoint point, Path path) throws IOException {
                if (point == IoUtils.FaultPoint.AFTER_REPLACEMENT) {
                    throw new IOException("publication failed after mutation");
                }
                if (point == IoUtils.FaultPoint.BEFORE_ROLLBACK
                        && rollbackCalls[0]++ == 0) {
                    throw new IOException("transient restore lock");
                }
            }
        };
        IoUtils.Sleeper rejectingSleeper = new IoUtils.Sleeper() {
            @Override
            public void sleep(long millis) {
                sleeps[0]++;
                fail("rollback sleep must be skipped while interruption is pending");
            }
        };
        IoUtils.FileMover copyingMover = new IoUtils.FileMover() {
            @Override
            public void move(Path from, Path to, CopyOption... options) throws IOException {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        };

        Thread.currentThread().interrupt();
        try {
            IoUtils.moveReplacing(source, target, copyingMover, rejectingSleeper, faults);
            fail("expected publication failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("publication failed"));
            assertTrue("interruption must be restored after rollback",
                    Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertEquals(0, sleeps[0]);
        assertTrue("all required rollback attempts should run", rollbackCalls[0] >= 2);
        assertArrayEquals(bytes("prior"), Files.readAllBytes(target));
        assertNoTransactionArtifacts();
    }

    @Test
    public void transientCleanupFaultIsRetriedWithoutChangingPublishedBytes() throws Exception {
        Path source = write("cleanup.tmp", bytes("candidate"));
        Path target = write("cleanup.json", bytes("old"));
        final int[] candidateCleanupCalls = new int[] {0};
        IoUtils.FaultInjector faults = new IoUtils.FaultInjector() {
            @Override
            public void checkpoint(IoUtils.FaultPoint point, Path path) throws IOException {
                if (point == IoUtils.FaultPoint.BEFORE_CLEANUP
                        && path.getFileName().toString().endsWith(".candidate")
                        && candidateCleanupCalls[0]++ == 0) {
                    throw new IOException("transient cleanup lock");
                }
            }
        };

        IoUtils.commitReplacingSmallFile(source, target, realMover(), NO_SLEEP, faults);

        assertTrue("candidate cleanup should have retried", candidateCleanupCalls[0] >= 2);
        assertArrayEquals(bytes("candidate"), Files.readAllBytes(target));
        assertFalse(Files.exists(source));
        assertNoTransactionArtifacts();
    }

    @Test
    public void cleanupFailureCannotMaskVmFatalPublicationFailure() throws Exception {
        Path source = write("fatal.tmp", bytes("candidate"));
        Path target = write("fatal.bin", bytes("prior"));
        final int[] cleanupFaults = new int[] {0};
        IoUtils.FaultInjector faults = new IoUtils.FaultInjector() {
            @Override
            public void checkpoint(IoUtils.FaultPoint point, Path path) throws IOException {
                if (point == IoUtils.FaultPoint.AFTER_REPLACEMENT) {
                    throw new TestVmError("fatal publication failure");
                }
                if (point == IoUtils.FaultPoint.BEFORE_CLEANUP && cleanupFaults[0]++ < 3) {
                    throw new IOException("injected persistent first-pass cleanup failure");
                }
            }
        };
        IoUtils.FileMover copyingMover = new IoUtils.FileMover() {
            @Override
            public void move(Path from, Path to, CopyOption... options) throws IOException {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        };

        try {
            IoUtils.moveReplacing(source, target, copyingMover, NO_SLEEP, faults);
            fail("expected VM-fatal failure");
        } catch (TestVmError expected) {
            assertEquals("fatal publication failure", expected.getMessage());
            assertTrue("cleanup failure should be suppressed on the VM-fatal primary",
                    expected.getSuppressed().length >= 1);
        }

        assertArrayEquals(bytes("prior"), Files.readAllBytes(target));
        assertArrayEquals(bytes("candidate"), Files.readAllBytes(source));
        assertNoTransactionArtifacts();
    }

    @Test
    public void vmFatalRemainsPrimaryWhenRollbackAndSafeCleanupBothFail() throws Exception {
        Path source = write("fatal-recovery.tmp", bytes("candidate"));
        Path target = write("fatal-recovery.bin", bytes("prior"));
        IoUtils.FaultInjector faults = new IoUtils.FaultInjector() {
            @Override
            public void checkpoint(IoUtils.FaultPoint point, Path path) throws IOException {
                if (point == IoUtils.FaultPoint.AFTER_REPLACEMENT) {
                    throw new TestVmError("fatal publication failure");
                }
                if (point == IoUtils.FaultPoint.BEFORE_ROLLBACK) {
                    throw new IOException("persistent fatal rollback failure");
                }
                if (point == IoUtils.FaultPoint.BEFORE_CLEANUP
                        && path.getFileName().toString().endsWith(".attempt")) {
                    throw new IOException("persistent redundant-attempt cleanup failure");
                }
            }
        };
        IoUtils.FileMover copyingMover = new IoUtils.FileMover() {
            @Override
            public void move(Path from, Path to, CopyOption... options) throws IOException {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        };

        try {
            IoUtils.moveReplacing(source, target, copyingMover, NO_SLEEP, faults);
            fail("expected VM-fatal failure");
        } catch (TestVmError expected) {
            assertEquals("fatal publication failure", expected.getMessage());
            String diagnostic = throwableDiagnostic(expected);
            assertTrue(diagnostic.contains("persistent fatal rollback failure"));
            assertTrue(diagnostic.contains("persistent redundant-attempt cleanup failure"));
            assertTrue(diagnostic.contains("Recovery artifacts retained"));
        }

        assertArrayEquals(bytes("prior"),
                Files.readAllBytes(findTransactionArtifact(".backup")));
        assertArrayEquals(bytes("candidate"),
                Files.readAllBytes(findTransactionArtifact(".candidate")));
        assertArrayEquals(bytes("candidate"), Files.readAllBytes(source));
    }

    @Test
    public void failedPublicationToAbsentTargetLeavesTargetAbsent() throws Exception {
        Path source = write("absent.tmp", bytes("candidate"));
        Path target = new File(temp.getRoot(), "absent.bin").toPath();

        try {
            IoUtils.moveReplacing(source, target, mutatingRejectedMover(), NO_SLEEP);
            fail("expected replacement failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Could not replace"));
        }

        assertFalse("a previously absent target must remain absent", Files.exists(target));
        assertArrayEquals(bytes("candidate"), Files.readAllBytes(source));
        assertNoTransactionArtifacts();
    }

    @Test
    public void backupFailureIsNeverIgnoredAndDoesNotPublishCandidate() throws Exception {
        Path source = write("backup.tmp", bytes("candidate"));
        Path target = write("backup.json", bytes("prior"));

        try {
            IoUtils.commitReplacingSmallFile(source, target, realMover(), NO_SLEEP,
                    failAlways(IoUtils.FaultPoint.AFTER_BACKUP,
                            "injected backup verification failure"));
            fail("expected backup failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("backup verification"));
        }

        assertArrayEquals(bytes("prior"), Files.readAllBytes(target));
        assertFalse(Files.exists(source));
        assertNoTransactionArtifacts();
    }

    private IoUtils.FileMover realMover() {
        return new IoUtils.FileMover() {
            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                Files.move(source, target, options);
            }
        };
    }

    private IoUtils.FileMover mutatingRejectedMover() {
        return new IoUtils.FileMover() {
            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                Files.write(target, new byte[] {42, 0});
                throw new IOException("provider rejected move after mutating target");
            }
        };
    }

    private static IoUtils.FaultInjector failAlways(final IoUtils.FaultPoint selected,
                                                     final String message) {
        return new IoUtils.FaultInjector() {
            @Override
            public void checkpoint(IoUtils.FaultPoint point, Path path) throws IOException {
                if (point == selected) {
                    throw new IOException(message);
                }
            }
        };
    }

    private Path write(String name, byte[] content) throws IOException {
        Path path = new File(temp.getRoot(), name).toPath();
        Files.write(path, content);
        return path;
    }

    private Path findTransactionArtifact(String suffix) throws IOException {
        Path match = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(temp.getRoot().toPath())) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.startsWith(".flash-") && name.endsWith(suffix)) {
                    if (match != null) {
                        fail("multiple transaction artifacts ending in " + suffix);
                    }
                    match = file;
                }
            }
        }
        assertTrue("missing transaction artifact ending in " + suffix, match != null);
        return match;
    }

    private static String throwableDiagnostic(Throwable failure) {
        StringBuilder diagnostic = new StringBuilder();
        appendThrowable(diagnostic, failure);
        return diagnostic.toString();
    }

    private static void appendThrowable(StringBuilder diagnostic, Throwable failure) {
        if (failure == null) {
            return;
        }
        diagnostic.append(failure.toString()).append('\n');
        appendThrowable(diagnostic, failure.getCause());
        for (Throwable suppressed : failure.getSuppressed()) {
            appendThrowable(diagnostic, suppressed);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02x", Integer.valueOf(value & 0xff)));
        }
        return hex.toString();
    }

    private void assertNoTransactionArtifacts() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(temp.getRoot().toPath())) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                assertFalse("transaction artifact leaked: " + name, name.startsWith(".flash-"));
                assertFalse("legacy unverified backup leaked: " + name, name.endsWith(".bak"));
            }
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class TestVmError extends VirtualMachineError {
        private TestVmError(String message) {
            super(message);
        }
    }
}
