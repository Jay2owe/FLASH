package flash.pipeline.lines;

import flash.pipeline.ui.wizard.JsonIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bundled landmark vocabulary used by Line Distance Analysis to pre-fill
 * common reference-line names (ventricle wall, pial surface, etc.).
 *
 * <p>The "Custom" label is the sentinel for a free-form name typed into
 * the inline name field.
 */
public final class LineVocab {

    public static final String CUSTOM_LABEL = "Custom";
    private static final String RESOURCE_PATH = "/line_vocab.json";
    private static LineVocab bundled;

    private final int vocabularyVersion;
    private final List<Entry> entries;
    private final Map<String, Entry> byLabel;

    public LineVocab(int vocabularyVersion, List<Entry> entries) {
        this.vocabularyVersion = vocabularyVersion;
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
        Map<String, Entry> mutable = new LinkedHashMap<String, Entry>();
        for (Entry entry : this.entries) {
            mutable.put(entry.getLabel().toLowerCase(Locale.ROOT), entry);
        }
        this.byLabel = Collections.unmodifiableMap(mutable);
    }

    public static synchronized LineVocab loadBundled() throws IOException {
        if (bundled != null) {
            return bundled;
        }
        InputStream stream = LineVocab.class.getResourceAsStream(RESOURCE_PATH);
        if (stream == null) {
            throw new IOException("Missing bundled line vocabulary resource: " + RESOURCE_PATH);
        }
        try {
            bundled = fromJson(readFully(stream));
            return bundled;
        } finally {
            stream.close();
        }
    }

    public static LineVocab fromJson(String json) throws IOException {
        Map<String, Object> root = JsonIO.parseObject(json);
        int version = JsonIO.intValue(root.get("vocabularyVersion"), 0);
        List<Entry> parsed = new ArrayList<Entry>();
        for (Object obj : JsonIO.asList(root.get("entries"))) {
            Map<String, Object> entryObj = JsonIO.asObject(obj);
            String label = JsonIO.stringValue(entryObj.get("label"));
            if (label == null || label.trim().isEmpty()) {
                continue;
            }
            List<String> aliases = new ArrayList<String>();
            for (Object aliasObj : JsonIO.asList(entryObj.get("aliases"))) {
                if (aliasObj != null) {
                    aliases.add(String.valueOf(aliasObj));
                }
            }
            parsed.add(new Entry(label, aliases));
        }
        return new LineVocab(version, parsed);
    }

    public int vocabularyVersion() {
        return vocabularyVersion;
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<String> labels() {
        List<String> out = new ArrayList<String>();
        for (Entry entry : entries) {
            out.add(entry.getLabel());
        }
        return out;
    }

    /** Returns the entry whose label or alias matches {@code text} (case-insensitive), or null. */
    public Entry findMatch(String text) {
        if (text == null) return null;
        String needle = text.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return null;

        Entry direct = byLabel.get(needle);
        if (direct != null) return direct;

        for (Entry entry : entries) {
            for (String alias : entry.getAliases()) {
                if (alias.toLowerCase(Locale.ROOT).equals(needle)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Returns the existing set name (from {@code existingSetNames}) that matches
     * {@code candidate} either by exact case-insensitive match, alias match,
     * or by a vocab-label match where one of the entry's aliases matches the
     * existing set name. Returns null if no match.
     */
    public String matchExistingSetName(String candidate, List<String> existingSetNames) {
        if (candidate == null || existingSetNames == null || existingSetNames.isEmpty()) {
            return null;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) return null;
        String lower = trimmed.toLowerCase(Locale.ROOT);

        for (String existing : existingSetNames) {
            if (existing != null && existing.toLowerCase(Locale.ROOT).equals(lower)) {
                return existing;
            }
        }

        Entry match = findMatch(trimmed);
        if (match == null) return null;

        for (String existing : existingSetNames) {
            if (existing == null) continue;
            String existingLower = existing.toLowerCase(Locale.ROOT);
            if (existingLower.equals(match.getLabel().toLowerCase(Locale.ROOT))) {
                return existing;
            }
            for (String alias : match.getAliases()) {
                if (existingLower.equals(alias.toLowerCase(Locale.ROOT))) {
                    return existing;
                }
            }
        }
        return null;
    }

    private static String readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    public static final class Entry {
        private final String label;
        private final List<String> aliases;

        public Entry(String label, List<String> aliases) {
            this.label = label;
            this.aliases = Collections.unmodifiableList(
                    new ArrayList<String>(aliases == null ? Collections.<String>emptyList() : aliases));
        }

        public String getLabel() {
            return label;
        }

        public List<String> getAliases() {
            return aliases;
        }

        public boolean isCustom() {
            return CUSTOM_LABEL.equalsIgnoreCase(label);
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
