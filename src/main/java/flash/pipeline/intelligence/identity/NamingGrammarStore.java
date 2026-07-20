package flash.pipeline.intelligence.identity;

import flash.pipeline.intelligence.MiniJson;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;
import flash.pipeline.naming.ChannelFilenameCodec;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Transactional on-disk store for project naming grammars. */
public final class NamingGrammarStore {

    public static final String DIR_NAME = "naming_grammars";
    private static final int MAX_SAFE_BASENAME_CHARACTERS = 200;

    /** Typed states let the UI distinguish absence from damaged/unsafe data. */
    public enum LoadState { ABSENT, OK, CORRUPT, UNSUPPORTED, UNSAFE }

    /** A typed load outcome with an actionable diagnostic. */
    public static final class LoadResult {
        public final LoadState state;
        public final NamingGrammar grammar;
        public final String diagnostic;

        private LoadResult(LoadState state, NamingGrammar grammar, String diagnostic) {
            this.state = state;
            this.grammar = grammar;
            this.diagnostic = diagnostic == null ? "" : diagnostic;
        }

        public boolean isOk() {
            return state == LoadState.OK && grammar != null;
        }
    }

    /** Test seam for simulating a failed/corrupting final publication. */
    interface ReplaceOperation {
        void replace(Path source, Path target) throws IOException;
    }

    private static final ReplaceOperation DEFAULT_REPLACE = new ReplaceOperation() {
        @Override
        public void replace(Path source, Path target) throws IOException {
            IoUtils.moveReplacing(source, target);
        }
    };

    private NamingGrammarStore() {}

    /** The {@code naming_grammars} directory for a project (not created here). */
    public static File dir(String projectDir) {
        File settings = FlashProjectLayout.forDirectory(projectDir).configurationWriteDir();
        return new File(settings, DIR_NAME);
    }

    /**
     * Save through a verified sibling generation. A verified rolling backup is
     * made before replacement, and any failed/corrupt publication restores the
     * exact previous good bytes.
     */
    public static void save(String projectDir, NamingGrammar grammar) throws IOException {
        save(projectDir, grammar, DEFAULT_REPLACE);
    }

    static void save(String projectDir, NamingGrammar grammar, ReplaceOperation replace)
            throws IOException {
        if (grammar == null) throw new IOException("Cannot save a null grammar.");
        if (replace == null) throw new IOException("Grammar publisher is missing.");
        String grammarName = requireName(grammar.name);
        byte[] candidate;
        try {
            candidate = NamingGrammarCodec.toJson(grammar).getBytes(StandardCharsets.UTF_8);
        } catch (ValuePattern.UnsafePatternException e) {
            throw new NamingGrammarCodec.UnsafeGrammarException(e.getMessage(), e);
        }

        File directory = dir(projectDir);
        IoUtils.mustMkdirs(directory);
        Path target = resolveFile(directory, grammarName).toPath();
        rejectCaseInsensitiveCollision(directory, grammarName, target);

        Path temp = Files.createTempFile(directory.toPath(), ".grammar-", ".tmp");
        byte[] previous = null;
        try {
            Files.write(temp, candidate);
            verifyGeneration(temp, candidate);

            if (Files.isRegularFile(target)) {
                NamingGrammar current = decode(target, MiniJson.DEFAULT_LIMITS);
                NamingGrammarCodec.validate(current);
                long size = Files.size(target);
                if (size > MiniJson.DEFAULT_LIMITS.getMaxUtf8Bytes()) {
                    throw new IOException("Existing grammar is too large to preserve safely: " + target);
                }
                previous = Files.readAllBytes(target);
                publishBackup(target, previous);
            }

            try {
                replace.replace(temp, target);
                verifyGeneration(target, candidate);
            } catch (IOException e) {
                restorePrevious(target, previous, e);
                throw e;
            } catch (RuntimeException e) {
                IOException failure = new IOException("Grammar publication failed: " + e.getMessage(), e);
                restorePrevious(target, previous, failure);
                throw failure;
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Load a grammar by name; throws if the file is absent or invalid. */
    public static NamingGrammar load(String projectDir, String name) throws IOException {
        return load(projectDir, name, MiniJson.DEFAULT_LIMITS);
    }

    /** Package-visible bounded-read seam used by persistence contract tests. */
    static NamingGrammar load(String projectDir, String name, MiniJson.Limits limits)
            throws IOException {
        String checkedName = requireName(name);
        File file = resolveFile(dir(projectDir), checkedName);
        if (!file.isFile()) {
            throw new IOException("No saved grammar named '" + checkedName
                    + "' at " + file.getAbsolutePath());
        }
        return decode(file.toPath(), limits);
    }

    /** Inspect a saved grammar without collapsing distinct failure states. */
    public static LoadResult loadResult(String projectDir, String name) {
        File expected;
        try {
            String checkedName = requireName(name);
            expected = resolveFile(dir(projectDir), checkedName);
        } catch (UnsafeNameException e) {
            return result(LoadState.UNSAFE, null, e.getMessage());
        } catch (RuntimeException e) {
            return result(LoadState.UNSAFE, null, "Unsafe grammar location: " + e.getMessage());
        }
        if (!expected.isFile()) {
            return result(LoadState.ABSENT, null,
                    "No saved grammar named '" + name + "' was found.");
        }
        try {
            return result(LoadState.OK, load(projectDir, name), "");
        } catch (NamingGrammarCodec.UnsupportedVersionException e) {
            return result(LoadState.UNSUPPORTED, null, e.getMessage());
        } catch (NamingGrammarCodec.UnsafeGrammarException e) {
            return result(LoadState.UNSAFE, null, e.getMessage());
        } catch (UnsafeNameException e) {
            return result(LoadState.UNSAFE, null, e.getMessage());
        } catch (IOException e) {
            return result(LoadState.CORRUPT, null,
                    "Saved grammar '" + name + "' is corrupt: " + e.getMessage());
        } catch (ValuePattern.UnsafePatternException e) {
            return result(LoadState.UNSAFE, null, e.getMessage());
        } catch (RuntimeException e) {
            return result(LoadState.CORRUPT, null,
                    "Saved grammar '" + name + "' is invalid: " + e.getMessage());
        }
    }

    /** Compatibility helper. New UI code should use {@link #loadResult}. */
    public static NamingGrammar loadIfExists(String projectDir, String name) {
        LoadResult result = loadResult(projectDir, name);
        if (result.isOk()) return result.grammar;
        if (result.state != LoadState.ABSENT) {
            IJ.log("[FLASH] Could not read naming grammar '" + name + "' ["
                    + result.state + "]: " + result.diagnostic);
        }
        return null;
    }

    /** Saved grammar names, decoded losslessly from safe filename segments. */
    public static List<String> listNames(String projectDir) {
        List<String> names = new ArrayList<String>();
        File directory = dir(projectDir);
        File[] files = directory.listFiles();
        if (files == null) return names;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                String base = name.substring(0, name.length() - ".json".length());
                try {
                    NamingGrammar decoded = decode(file.toPath(), MiniJson.DEFAULT_LIMITS);
                    names.add(decoded.name);
                } catch (IOException e) {
                    // Keep damaged entries selectable so the dialog can surface
                    // CORRUPT/UNSUPPORTED/UNSAFE instead of hiding them.
                    names.add(base);
                } catch (RuntimeException e) {
                    names.add(base);
                }
            }
        }
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int folded = String.CASE_INSENSITIVE_ORDER.compare(left, right);
                return folded != 0 ? folded : left.compareTo(right);
            }
        });
        return names;
    }

    public static boolean hasAny(String projectDir) {
        return !listNames(projectDir).isEmpty();
    }

    private static NamingGrammar decode(Path file, MiniJson.Limits limits) throws IOException {
        Object parsed;
        try (InputStream input = Files.newInputStream(file)) {
            parsed = MiniJson.parseUtf8(input, limits, file.toAbsolutePath().toString());
        }
        return NamingGrammarCodec.fromParsed(parsed);
    }

    private static void verifyGeneration(Path file, byte[] expected) throws IOException {
        if (Files.size(file) != expected.length) {
            throw new IOException("Naming grammar generation has an unexpected byte length: "
                    + file.toAbsolutePath());
        }
        byte[] actual = Files.readAllBytes(file);
        if (!Arrays.equals(expected, actual)) {
            throw new IOException("Naming grammar generation did not preserve its exact UTF-8 bytes: "
                    + file.toAbsolutePath());
        }
        NamingGrammar decoded = decode(file, MiniJson.DEFAULT_LIMITS);
        byte[] canonical = NamingGrammarCodec.toJson(decoded).getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(expected, canonical)) {
            throw new IOException("Naming grammar generation did not round-trip canonically: "
                    + file.toAbsolutePath());
        }
    }

    private static void publishBackup(Path target, byte[] previous) throws IOException {
        Path backup = target.resolveSibling(target.getFileName().toString() + ".bak");
        Path tempBackup = Files.createTempFile(target.getParent(), ".grammar-backup-", ".tmp");
        try {
            Files.write(tempBackup, previous);
            if (!Arrays.equals(previous, Files.readAllBytes(tempBackup))) {
                throw new IOException("Could not verify previous naming grammar backup bytes.");
            }
            IoUtils.moveReplacing(tempBackup, backup);
            if (!Arrays.equals(previous, Files.readAllBytes(backup))) {
                throw new IOException("Previous naming grammar backup failed verification: " + backup);
            }
        } finally {
            Files.deleteIfExists(tempBackup);
        }
    }

    private static void restorePrevious(Path target, byte[] previous, IOException publicationFailure)
            throws IOException {
        try {
            if (previous == null) {
                Files.deleteIfExists(target);
                return;
            }
            Path restore = Files.createTempFile(target.getParent(), ".grammar-restore-", ".tmp");
            try {
                Files.write(restore, previous);
                IoUtils.commitReplacingSmallFile(restore, target);
            } finally {
                Files.deleteIfExists(restore);
            }
            if (!Arrays.equals(previous, Files.readAllBytes(target))) {
                throw new IOException("Previous naming grammar bytes could not be restored.");
            }
        } catch (IOException restoreFailure) {
            restoreFailure.addSuppressed(publicationFailure);
            throw restoreFailure;
        }
    }

    private static void rejectCaseInsensitiveCollision(File directory, String rawName, Path target)
            throws IOException {
        String wantedKey = ChannelFilenameCodec.windowsCollisionKey(rawName);
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String fileName = file.getName();
            if (!file.isFile() || !fileName.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
            String rawExisting;
            try {
                rawExisting = decode(file.toPath(), MiniJson.DEFAULT_LIMITS).name;
            } catch (Exception e) {
                rawExisting = fileName.substring(0, fileName.length() - ".json".length());
            }
            if (!wantedKey.equals(ChannelFilenameCodec.windowsCollisionKey(rawExisting))) continue;
            if (!rawName.equals(rawExisting)) {
                throw new UnsafeNameException("Grammar name '" + rawName
                        + "' collides case-insensitively with existing grammar '"
                        + rawExisting + "'. Choose a distinct name.");
            }
            if (!file.toPath().toAbsolutePath().normalize()
                    .equals(target.toAbsolutePath().normalize())) {
                throw new UnsafeNameException("Grammar name resolves to an ambiguous file: " + rawName);
            }
        }
    }

    private static String requireName(String name) throws UnsafeNameException {
        if (name == null || name.trim().isEmpty()) {
            throw new UnsafeNameException("Grammar name must not be blank.");
        }
        String safe = ChannelFilenameCodec.toSafe(name);
        if (safe == null || safe.isEmpty() || safe.length() > MAX_SAFE_BASENAME_CHARACTERS) {
            throw new UnsafeNameException("Grammar name is too long for a portable filename (maximum encoded length "
                    + MAX_SAFE_BASENAME_CHARACTERS + ").");
        }
        return name;
    }

    private static String fileName(String name) throws UnsafeNameException {
        String checked = requireName(name);
        return ChannelFilenameCodec.toSafe(checked) + ".json";
    }

    private static File resolveFile(File directory, String rawName) throws UnsafeNameException {
        File current = new File(directory, fileName(rawName));
        if (current.isFile()) return current;
        // Read/overwrite the pre-versioning filename convention in place. This
        // keeps existing lab grammars discoverable while all new names use the
        // reversible codec above.
        String legacyBase = rawName.trim().replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
        if (legacyBase.isEmpty()) legacyBase = "grammar";
        File legacy = new File(directory, legacyBase + ".json");
        return legacy.isFile() ? legacy : current;
    }

    private static LoadResult result(LoadState state, NamingGrammar grammar, String diagnostic) {
        return new LoadResult(state, grammar, diagnostic);
    }

    private static final class UnsafeNameException extends IOException {
        UnsafeNameException(String message) {
            super(message);
        }
    }
}
