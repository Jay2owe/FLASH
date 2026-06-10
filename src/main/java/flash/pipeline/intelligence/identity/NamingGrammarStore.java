package flash.pipeline.intelligence.identity;

import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * On-disk store for saved {@link NamingGrammar}s, one JSON file per grammar under
 * {@code FLASH/Config/.settings/naming_grammars/}. Pairs with
 * {@link NamingGrammarCodec} for (de)serialisation (Stage 07b).
 */
public final class NamingGrammarStore {

    public static final String DIR_NAME = "naming_grammars";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private NamingGrammarStore() {}

    /** The {@code naming_grammars} directory for a project (not created here). */
    public static File dir(String projectDir) {
        File settings = FlashProjectLayout.forDirectory(projectDir).configurationWriteDir();
        return new File(settings, DIR_NAME);
    }

    /** Save a grammar as {@code <name>.json}; creates the directory if needed. */
    public static void save(String projectDir, NamingGrammar grammar) throws IOException {
        if (grammar == null) throw new IOException("Cannot save a null grammar.");
        File dir = dir(projectDir);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create grammar directory: " + dir.getAbsolutePath());
        }
        File file = new File(dir, fileName(grammar.name));
        Files.write(file.toPath(), NamingGrammarCodec.toJson(grammar).getBytes(UTF8));
    }

    /** Load a grammar by name; throws if the file is missing or unparseable. */
    public static NamingGrammar load(String projectDir, String name) throws IOException {
        File file = new File(dir(projectDir), fileName(name));
        if (!file.isFile()) {
            throw new IOException("No saved grammar named '" + name + "' at " + file.getAbsolutePath());
        }
        return NamingGrammarCodec.fromJson(new String(Files.readAllBytes(file.toPath()), UTF8));
    }

    /** Load a grammar by name, or {@code null} when missing/unreadable. */
    public static NamingGrammar loadIfExists(String projectDir, String name) {
        try {
            return load(projectDir, name);
        } catch (IOException e) {
            return null;
        }
    }

    /** Saved grammar names (file base names, without {@code .json}), sorted. */
    public static List<String> listNames(String projectDir) {
        List<String> names = new ArrayList<String>();
        File dir = dir(projectDir);
        File[] files = dir.listFiles();
        if (files == null) return names;
        for (File f : files) {
            String n = f.getName();
            if (f.isFile() && n.toLowerCase(Locale.ROOT).endsWith(".json")) {
                names.add(n.substring(0, n.length() - ".json".length()));
            }
        }
        Collections.sort(names);
        return names;
    }

    /** True when at least one grammar is saved. */
    public static boolean hasAny(String projectDir) {
        return !listNames(projectDir).isEmpty();
    }

    private static String fileName(String name) {
        String base = name == null ? "" : name.trim();
        if (base.isEmpty()) base = "grammar";
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        return base + ".json";
    }
}
