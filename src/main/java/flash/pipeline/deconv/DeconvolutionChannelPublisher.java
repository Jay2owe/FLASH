package flash.pipeline.deconv;

import flash.pipeline.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Transactional publisher for one identity-bound channel TIFF + manifest entry. */
public final class DeconvolutionChannelPublisher {

    private DeconvolutionChannelPublisher() {}

    enum FaultPoint {
        AFTER_MIRROR_PUBLISH,
        AFTER_MANIFEST_COMMIT_BEFORE_VALIDATION
    }

    interface FaultInjector {
        void checkpoint(FaultPoint point) throws IOException;
    }

    private static final FaultInjector NO_FAULTS = new FaultInjector() {
        @Override
        public void checkpoint(FaultPoint point) {}
    };

    public static File publish(File rootDir,
                               DeconvolutionIO.ArtifactIdentity identity,
                               int channelIndex,
                               File stagedTiff,
                               DeconvManifest.ChannelEntry entry) throws IOException {
        return publish(rootDir, identity, channelIndex, stagedTiff, entry, NO_FAULTS);
    }

    static File publishForTest(File rootDir,
                               DeconvolutionIO.ArtifactIdentity identity,
                               int channelIndex,
                               File stagedTiff,
                               DeconvManifest.ChannelEntry entry,
                               FaultInjector faults) throws IOException {
        return publish(rootDir, identity, channelIndex, stagedTiff, entry,
                faults == null ? NO_FAULTS : faults);
    }

    private static File publish(File rootDir,
                                DeconvolutionIO.ArtifactIdentity identity,
                                int channelIndex,
                                File stagedTiff,
                                DeconvManifest.ChannelEntry entry,
                                FaultInjector faults) throws IOException {
        if (rootDir == null || identity == null || !identity.isPublishable()
                || stagedTiff == null
                || !Files.isRegularFile(stagedTiff.toPath(), LinkOption.NOFOLLOW_LINKS)
                || entry == null) {
            throw new IOException("A staged TIFF, channel entry, and publishable identity are required.");
        }
        File target = DeconvolutionIO.deconvFile(rootDir, identity, channelIndex);
        File manifestFile = DeconvolutionIO.manifestFile(rootDir, identity);
        File transaction = new File(new File(DeconvolutionIO.cacheDir(rootDir), ".publication"),
                identity.familyLockToken() + "-" + UUID.randomUUID().toString());
        File desiredTiff = new File(transaction, "desired.tif");
        IOException failure = null;
        boolean success = false;
        boolean cleanupAllowed = true;
        try {
            IoUtils.mustMkdirs(transaction);
            Files.copy(stagedTiff.toPath(), desiredTiff.toPath(), StandardCopyOption.REPLACE_EXISTING);
            DeconvManifest.SourceFingerprint desiredFingerprint =
                    DeconvManifest.SourceFingerprint.of(desiredTiff);

            try (DeconvolutionFamilyLock.Handle ignored =
                         DeconvolutionIO.lockFamilyForAccess(rootDir, identity)) {
                if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Deconvolution channel target is not a regular file: " + target);
                }
                if (Files.exists(manifestFile.toPath(), LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(manifestFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Deconvolution manifest target is not a regular file: "
                            + manifestFile);
                }
                DeconvManifest priorManifest = DeconvManifest.load(manifestFile);
                DeconvManifest desiredManifest = priorManifest.withArtifactIdentity(identity)
                        .withChannel(channelIndex, entry);
                File manifestBackup = new File(transaction, "manifest-backup.json");
                boolean manifestExisted = manifestFile.isFile();
                if (manifestExisted) {
                    Files.copy(manifestFile.toPath(), manifestBackup.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                File targetBackup = new File(transaction, "target-backup.tif");
                boolean targetExisted = target.isFile();
                if (targetExisted) {
                    Files.copy(target.toPath(), targetBackup.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    IoUtils.mustMkdirs(target.getParentFile());
                    File publishTiff = new File(transaction, "publish.tif");
                    Files.copy(desiredTiff.toPath(), publishTiff.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    IoUtils.moveReplacing(publishTiff.toPath(), target.toPath());
                    faults.checkpoint(FaultPoint.AFTER_MIRROR_PUBLISH);
                    DeconvManifest.writeAtomic(manifestFile, desiredManifest);
                    faults.checkpoint(FaultPoint.AFTER_MANIFEST_COMMIT_BEFORE_VALIDATION);
                    if (!matches(target, desiredFingerprint)
                            || !desiredManifest.toJson().equals(
                                    DeconvManifest.load(manifestFile).toJson())) {
                        throw new IOException("Published deconvolution channel pair failed validation.");
                    }
                    success = true;
                } catch (IOException publicationFailure) {
                    failure = publicationFailure;
                    try {
                        restoreOrForward(target, manifestFile,
                                targetExisted, targetBackup, manifestExisted, manifestBackup,
                                desiredTiff, desiredFingerprint, desiredManifest, publicationFailure);
                    } catch (IOException unsafeFailure) {
                        failure = unsafeFailure;
                        cleanupAllowed = false;
                    }
                }
            }
        } catch (IOException setupOrLockFailure) {
            if (failure == null) failure = setupOrLockFailure;
            else failure.addSuppressed(setupOrLockFailure);
        } finally {
            try {
                Files.deleteIfExists(stagedTiff.toPath());
            } catch (IOException stagedCleanupFailure) {
                if (failure != null) failure.addSuppressed(stagedCleanupFailure);
                else failure = stagedCleanupFailure;
            }
            if (cleanupAllowed) {
                try {
                    deletePlainTree(transaction);
                } catch (IOException cleanupFailure) {
                    if (failure != null) failure.addSuppressed(cleanupFailure);
                    else failure = cleanupFailure;
                }
            }
        }
        if (failure != null) throw failure;
        if (!success) throw new IOException("Deconvolution channel publication did not complete.");
        return target;
    }

    private static void restoreOrForward(File target,
                                         File manifestFile,
                                         boolean targetExisted,
                                         File targetBackup,
                                         boolean manifestExisted,
                                         File manifestBackup,
                                         File desiredTiff,
                                         DeconvManifest.SourceFingerprint desiredFingerprint,
                                         DeconvManifest desiredManifest,
                                         IOException primary) throws IOException {
        try {
            if (manifestExisted) {
                File restore = new File(manifestBackup.getParentFile(), "manifest-restore.json");
                Files.copy(manifestBackup.toPath(), restore.toPath(), StandardCopyOption.REPLACE_EXISTING);
                IoUtils.commitReplacingSmallFile(restore.toPath(), manifestFile.toPath());
            } else {
                Files.deleteIfExists(manifestFile.toPath());
            }
        } catch (IOException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
        try {
            if (targetExisted) {
                File restore = new File(targetBackup.getParentFile(), "target-restore.tif");
                Files.copy(targetBackup.toPath(), restore.toPath(), StandardCopyOption.REPLACE_EXISTING);
                IoUtils.moveReplacing(restore.toPath(), target.toPath());
            } else {
                Files.deleteIfExists(target.toPath());
            }
        } catch (IOException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
        boolean priorRestored = targetExisted == target.isFile()
                && (!targetExisted || sameContent(targetBackup, target))
                && (manifestExisted == manifestFile.isFile())
                && (!manifestExisted || sameContent(manifestBackup, manifestFile));
        if (priorRestored) return;

        try {
            File forward = new File(desiredTiff.getParentFile(), "forward.tif");
            Files.copy(desiredTiff.toPath(), forward.toPath(), StandardCopyOption.REPLACE_EXISTING);
            IoUtils.moveReplacing(forward.toPath(), target.toPath());
            DeconvManifest.writeAtomic(manifestFile, desiredManifest);
            if (matches(target, desiredFingerprint)
                    && desiredManifest.toJson().equals(DeconvManifest.load(manifestFile).toJson())) {
                return;
            }
        } catch (IOException forwardFailure) {
            primary.addSuppressed(forwardFailure);
        }
        throw new IOException("Could not restore or forward-complete deconvolution channel pair.", primary);
    }

    private static boolean matches(File file, DeconvManifest.SourceFingerprint expected)
            throws IOException {
        return file != null && file.isFile()
                && expected.matches(DeconvManifest.SourceFingerprint.of(file));
    }

    private static boolean sameContent(File first, File second) throws IOException {
        return first != null && second != null && first.isFile() && second.isFile()
                && DeconvManifest.SourceFingerprint.of(first)
                .matches(DeconvManifest.SourceFingerprint.of(second));
    }

    private static void deletePlainTree(File root) throws IOException {
        if (root == null || !Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        Path rootLexical = root.toPath().toAbsolutePath().normalize();
        deletePlainTree(rootLexical, rootLexical.toRealPath(), rootLexical, new HashSet<Path>());
    }

    private static void deletePlainTree(Path rootLexical, Path rootReal, Path candidate,
                                        Set<Path> visited) throws IOException {
        if (!candidate.toAbsolutePath().normalize().startsWith(rootLexical)
                || Files.isSymbolicLink(candidate)) {
            throw new IOException("Refusing to follow publication cleanup link: " + candidate);
        }
        BasicFileAttributes attributes = Files.readAttributes(candidate,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path real = candidate.toRealPath();
        Path expected = rootReal.resolve(rootLexical.relativize(candidate)).normalize();
        if (!real.equals(expected) || !real.startsWith(rootReal)) {
            throw new IOException("Refusing to follow publication cleanup junction: " + candidate);
        }
        if (attributes.isDirectory() && visited.add(real)) {
            File[] children = candidate.toFile().listFiles();
            if (children == null) {
                throw new IOException("Could not enumerate publication cleanup: " + candidate);
            }
            for (File child : children) {
                deletePlainTree(rootLexical, rootReal,
                        child.toPath().toAbsolutePath().normalize(), visited);
            }
        }
        Files.deleteIfExists(candidate);
    }
}
