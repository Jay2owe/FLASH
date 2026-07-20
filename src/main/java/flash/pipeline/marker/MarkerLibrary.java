package flash.pipeline.marker;

import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.MarkerSearchRanking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bundled marker metadata loaded from /marker_library/markers.json.
 */
public final class MarkerLibrary {

    private static final String RESOURCE_PATH = "/marker_library/markers.json";
    private static MarkerLibrary bundled;

    private final int libraryVersion;
    private final List<String> categories;
    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final Map<String, List<Entry>> byCategory;

    public MarkerLibrary(int libraryVersion, List<String> categories, List<Entry> entries) {
        this.libraryVersion = libraryVersion;
        this.categories = immutableStringList(categories);
        this.entries = immutableEntryList(entries);
        validateIdentities(this.entries);
        this.byId = indexById(this.entries);
        this.byCategory = indexByCategory(this.entries);
    }

    public static synchronized MarkerLibrary loadBundled() throws IOException {
        if (bundled != null) {
            return bundled;
        }
        InputStream stream = MarkerLibrary.class.getResourceAsStream(RESOURCE_PATH);
        if (stream == null) {
            throw new IOException("Missing bundled marker library resource: " + RESOURCE_PATH);
        }
        try {
            bundled = fromJson(readFully(stream));
            return bundled;
        } finally {
            stream.close();
        }
    }

    public static MarkerLibrary fromJson(String json) throws IOException {
        Map<String, Object> root = JsonIO.parseObject(json);
        int version = JsonIO.intValue(root.get("libraryVersion"), 0);
        List<String> categories = asStrings(JsonIO.asList(root.get("categories")));
        List<Entry> entries = new ArrayList<Entry>();
        List<Object> markerObjects = JsonIO.asList(root.get("markers"));
        for (Object markerObject : markerObjects) {
            entries.add(Entry.fromObject(JsonIO.asObject(markerObject)));
        }
        try {
            return new MarkerLibrary(version, categories, entries);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid marker library identity: " + e.getMessage(), e);
        }
    }

    public int libraryVersion() {
        return libraryVersion;
    }

    public List<String> categories() {
        return categories;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry byId(String id) {
        return byId.get(id == null ? "" : id.trim());
    }

    public Entry exactMatch(String text) {
        Resolution resolution = resolve(text);
        return resolution.candidates.size() == 1 ? resolution.candidates.get(0) : null;
    }

    /**
     * Resolves accepted marker text without first-wins behavior. Stable IDs
     * take precedence over exact display synonyms; normalized search is used
     * only after both exact tiers miss.
     */
    public Resolution resolve(String text) {
        String accepted = text == null ? "" : text.trim();
        if (accepted.isEmpty()) {
            return new Resolution(MatchKind.NONE, Collections.<Entry>emptyList());
        }
        Entry stableId = byId(accepted);
        if (stableId != null) {
            return new Resolution(MatchKind.STABLE_ID,
                    Collections.singletonList(stableId));
        }
        List<Entry> synonyms = exactSynonymMatches(accepted);
        if (!synonyms.isEmpty()) {
            return new Resolution(MatchKind.EXACT_SYNONYM, synonyms);
        }
        List<Entry> normalized = normalizedMatches(accepted);
        if (!normalized.isEmpty()) {
            return new Resolution(MatchKind.NORMALIZED_SEARCH, normalized);
        }
        return new Resolution(MatchKind.NONE, Collections.<Entry>emptyList());
    }

    public List<Entry> exactSynonymMatches(String text) {
        String key = MarkerSearchRanking.exactKey(text);
        if (key.isEmpty()) {
            return Collections.emptyList();
        }
        List<Entry> matches = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (entry == null) continue;
            if (key.equals(MarkerSearchRanking.exactKey(entry.getDisplayName()))
                    || containsExactKey(entry.getAliases(), key)) {
                matches.add(entry);
            }
        }
        return immutableSortedById(matches);
    }

    /**
     * Returns every entry whose ID, display name, exact alias, or explicitly
     * ambiguous alias has the same Unicode-preserving normalized key. General
     * search hints remain advisory and never become persisted identities. The
     * list is stable-ID sorted so diagnostics and GUI choices are reproducible.
     */
    public List<Entry> normalizedMatches(String text) {
        String key = MarkerSearchRanking.normalize(text);
        if (key.isEmpty()) {
            return Collections.emptyList();
        }
        List<Entry> matches = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (entry == null) continue;
            if (key.equals(MarkerSearchRanking.normalize(entry.getId()))
                    || key.equals(MarkerSearchRanking.normalize(entry.getDisplayName()))
                    || containsNormalized(entry.getAliases(), key)
                    || containsNormalized(entry.getAmbiguousAliases(), key)) {
                matches.add(entry);
            }
        }
        return immutableSortedById(matches);
    }

    public List<Entry> byCategory(String category) {
        List<Entry> categoryEntries = byCategory.get(category == null ? "" : category.trim());
        return categoryEntries == null ? Collections.<Entry>emptyList() : categoryEntries;
    }

    public List<Entry> search(String query, int topN) {
        if (topN <= 0 || query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<SearchHit> hits = new ArrayList<SearchHit>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int rank = rank(entry, query);
            if (rank < MarkerSearchRanking.NO_MATCH) {
                hits.add(new SearchHit(entry, rank, i));
            }
        }
        Collections.sort(hits, new Comparator<SearchHit>() {
            public int compare(SearchHit left, SearchHit right) {
                if (left.rank != right.rank) {
                    return left.rank - right.rank;
                }
                int byStableId = left.entry.getId().compareTo(right.entry.getId());
                return byStableId != 0 ? byStableId : left.index - right.index;
            }
        });
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i < hits.size() && i < topN; i++) {
            out.add(hits.get(i).entry);
        }
        return out;
    }

    private static int rank(Entry entry, String query) {
        List<String> searchableAliases = new ArrayList<String>(entry.aliases);
        searchableAliases.addAll(entry.ambiguousAliases);
        return MarkerSearchRanking.rank(entry.id, entry.displayName,
                searchableAliases, entry.nameHints, query);
    }

    private static Map<String, Entry> indexById(List<Entry> entries) {
        Map<String, Entry> out = new LinkedHashMap<String, Entry>();
        for (Entry entry : entries) {
            if (entry == null || entry.getId().trim().isEmpty()) continue;
            out.put(entry.getId().trim(), entry);
        }
        return Collections.unmodifiableMap(out);
    }

    private static void validateIdentities(List<Entry> entries) {
        Map<String, String> ids = new LinkedHashMap<String, String>();
        Map<String, String> exactSynonyms = new LinkedHashMap<String, String>();
        for (Entry entry : entries) {
            if (entry == null) continue;
            String id = entry.getId().trim();
            if (!id.isEmpty()) {
                String idKey = MarkerSearchRanking.exactKey(id);
                String prior = ids.put(idKey, id);
                if (prior != null) {
                    throw new IllegalArgumentException(
                            "duplicate stable IDs '" + prior + "' and '" + id + "'");
                }
            }
            List<String> synonyms = new ArrayList<String>();
            synonyms.add(entry.getDisplayName());
            synonyms.addAll(entry.getAliases());
            Set<String> seenForEntry = new LinkedHashSet<String>();
            for (String synonym : synonyms) {
                String key = MarkerSearchRanking.exactKey(synonym);
                if (key.isEmpty() || !seenForEntry.add(key)) continue;
                String priorId = exactSynonyms.get(key);
                if (priorId != null && !priorId.equals(entry.getId())) {
                    throw new IllegalArgumentException(
                            "exact synonym '" + synonym + "' is shared by stable IDs '"
                                    + priorId + "' and '" + entry.getId() + "'");
                }
                exactSynonyms.put(key, entry.getId());
            }
        }
    }

    private static List<Entry> immutableSortedById(List<Entry> values) {
        List<Entry> sorted = new ArrayList<Entry>();
        Set<String> seenIds = new LinkedHashSet<String>();
        if (values != null) {
            for (Entry entry : values) {
                if (entry != null && seenIds.add(entry.getId())) {
                    sorted.add(entry);
                }
            }
        }
        Collections.sort(sorted, new Comparator<Entry>() {
            @Override public int compare(Entry left, Entry right) {
                return left.getId().compareTo(right.getId());
            }
        });
        return Collections.unmodifiableList(sorted);
    }

    private static Map<String, List<Entry>> indexByCategory(List<Entry> entries) {
        // An entry can sit in multiple categories — its primary `category` plus any
        // `additionalCategories`. byCategory("microglia") finds CD68 even though
        // CD68's primary category is "lysosomes" (or vice versa). De-dup per category
        // so an entry with overlapping category lists isn't listed twice.
        Map<String, List<Entry>> mutable = new LinkedHashMap<String, List<Entry>>();
        for (Entry entry : entries) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
            if (entry == null) continue;
            seen.add(entry.getCategory());
            seen.addAll(entry.getAdditionalCategories());
            for (String category : seen) {
                if (category == null || category.trim().isEmpty()) continue;
                category = category.trim();
                List<Entry> categoryEntries = mutable.get(category);
                if (categoryEntries == null) {
                    categoryEntries = new ArrayList<Entry>();
                    mutable.put(category, categoryEntries);
                }
                categoryEntries.add(entry);
            }
        }
        Map<String, List<Entry>> out = new LinkedHashMap<String, List<Entry>>();
        for (Map.Entry<String, List<Entry>> entry : mutable.entrySet()) {
            out.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static boolean containsNormalized(List<String> values, String needle) {
        if (values == null || needle == null || needle.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (needle.equals(MarkerSearchRanking.normalize(value))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsExactKey(List<String> values, String needle) {
        if (values == null || needle == null || needle.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (needle.equals(MarkerSearchRanking.exactKey(value))) {
                return true;
            }
        }
        return false;
    }

    private static String readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    private static List<String> immutableStringList(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    out.add(value.trim());
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Entry> immutableEntryList(List<Entry> values) {
        List<Entry> out = new ArrayList<Entry>();
        if (values != null) {
            for (Entry value : values) {
                if (value != null) {
                    out.add(value);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> asStrings(List<Object> values) {
        List<String> out = new ArrayList<String>();
        for (Object value : values) {
            if (value != null) {
                out.add(String.valueOf(value));
            }
        }
        return out;
    }

    private static final class SearchHit {
        private final Entry entry;
        private final int rank;
        private final int index;

        SearchHit(Entry entry, int rank, int index) {
            this.entry = entry;
            this.rank = rank;
            this.index = index;
        }
    }

    public enum MatchKind {
        NONE,
        STABLE_ID,
        EXACT_SYNONYM,
        NORMALIZED_SEARCH
    }

    public static final class Resolution {
        private final MatchKind kind;
        private final List<Entry> candidates;

        Resolution(MatchKind kind, List<Entry> candidates) {
            this.kind = kind == null ? MatchKind.NONE : kind;
            this.candidates = candidates == null
                    ? Collections.<Entry>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Entry>(candidates));
        }

        public MatchKind getKind() {
            return kind;
        }

        public List<Entry> getCandidates() {
            return candidates;
        }

        public boolean isAmbiguous() {
            return candidates.size() > 1;
        }
    }

    public static final class Entry {
        private final String id;
        private final String displayName;
        private final List<String> aliases;
        private final List<String> ambiguousAliases;
        private final String category;
        private final List<String> additionalCategories;
        private final String stainingPattern;
        private final String typicalObjectSizeNote;
        private final String conventionalLUT;
        private final String filterPreset;
        private final String shape;
        private final boolean crowdingSensitive;
        private final String particleSizeHint;
        private final String intensityLevel;
        private final List<String> nameHints;
        private final String notes;

        public Entry(String id,
                     String displayName,
                     List<String> aliases,
                     String category,
                     String stainingPattern,
                     String typicalObjectSizeNote,
                     String conventionalLUT,
                     String filterPreset,
                     String shape,
                     boolean crowdingSensitive,
                     String particleSizeHint,
                     String intensityLevel,
                     List<String> nameHints,
                     String notes) {
            this(id, displayName, aliases, category,
                    Collections.<String>emptyList(),
                    stainingPattern, typicalObjectSizeNote, conventionalLUT, filterPreset,
                    shape, crowdingSensitive, particleSizeHint, intensityLevel, nameHints, notes);
        }

        public Entry(String id,
                     String displayName,
                     List<String> aliases,
                     String category,
                     List<String> additionalCategories,
                     String stainingPattern,
                     String typicalObjectSizeNote,
                     String conventionalLUT,
                     String filterPreset,
                     String shape,
                     boolean crowdingSensitive,
                     String particleSizeHint,
                     String intensityLevel,
                     List<String> nameHints,
                     String notes) {
            this(id, displayName, aliases, Collections.<String>emptyList(), category,
                    additionalCategories, stainingPattern, typicalObjectSizeNote,
                    conventionalLUT, filterPreset, shape, crowdingSensitive,
                    particleSizeHint, intensityLevel, nameHints, notes);
        }

        private Entry(String id,
                      String displayName,
                      List<String> aliases,
                      List<String> ambiguousAliases,
                      String category,
                      List<String> additionalCategories,
                      String stainingPattern,
                      String typicalObjectSizeNote,
                      String conventionalLUT,
                      String filterPreset,
                      String shape,
                      boolean crowdingSensitive,
                      String particleSizeHint,
                      String intensityLevel,
                      List<String> nameHints,
                      String notes) {
            this.id = safe(id);
            this.displayName = safe(displayName);
            this.aliases = immutableStringList(aliases == null ? Collections.<String>emptyList() : aliases);
            this.ambiguousAliases = immutableStringList(
                    ambiguousAliases == null ? Collections.<String>emptyList() : ambiguousAliases);
            this.category = safe(category);
            this.additionalCategories = immutableStringList(
                    additionalCategories == null ? Collections.<String>emptyList() : additionalCategories);
            this.stainingPattern = safe(stainingPattern);
            this.typicalObjectSizeNote = safe(typicalObjectSizeNote);
            this.conventionalLUT = safe(conventionalLUT);
            this.filterPreset = safe(filterPreset);
            this.shape = safe(shape);
            this.crowdingSensitive = crowdingSensitive;
            this.particleSizeHint = safe(particleSizeHint);
            this.intensityLevel = safe(intensityLevel);
            this.nameHints = immutableStringList(nameHints == null ? Collections.<String>emptyList() : nameHints);
            this.notes = safe(notes);
        }

        static Entry fromObject(Map<String, Object> object) {
            return new Entry(
                    JsonIO.stringValue(object.get("id")),
                    JsonIO.stringValue(object.get("displayName")),
                    asStrings(JsonIO.asList(object.get("aliases"))),
                    asStrings(JsonIO.asList(object.get("ambiguousAliases"))),
                    JsonIO.stringValue(object.get("category")),
                    asStrings(JsonIO.asList(object.get("additionalCategories"))),
                    JsonIO.stringValue(object.get("stainingPattern")),
                    JsonIO.stringValue(object.get("typicalObjectSizeNote")),
                    JsonIO.stringValue(object.get("conventionalLUT")),
                    JsonIO.stringValue(object.get("filterPreset")),
                    JsonIO.stringValue(object.get("shape")),
                    JsonIO.booleanValue(object.get("crowdingSensitive"), false),
                    JsonIO.stringValue(object.get("particleSizeHint")),
                    JsonIO.stringValue(object.get("intensityLevel")),
                    asStrings(JsonIO.asList(object.get("nameHints"))),
                    JsonIO.stringValue(object.get("notes")));
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public List<String> getAliases() {
            return aliases;
        }

        /**
         * Terms deliberately shared by multiple biological entries. They are
         * searchable but never exact synonyms, so callers must disambiguate
         * the complete normalized candidate list.
         */
        public List<String> getAmbiguousAliases() {
            return ambiguousAliases;
        }

        public String getCategory() {
            return category;
        }

        /** Other categories this marker also fits in (besides {@link #getCategory()}). */
        public List<String> getAdditionalCategories() {
            return additionalCategories;
        }

        /** All categories this marker is filed under (primary first, then any additional, deduped). */
        public List<String> getAllCategories() {
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<String>();
            if (category != null && !category.isEmpty()) all.add(category);
            for (String c : additionalCategories) {
                if (c != null && !c.isEmpty()) all.add(c);
            }
            return Collections.unmodifiableList(new ArrayList<String>(all));
        }

        public String getStainingPattern() {
            return stainingPattern;
        }

        public String getTypicalObjectSizeNote() {
            return typicalObjectSizeNote;
        }

        public String getConventionalLUT() {
            return conventionalLUT;
        }

        public String getFilterPreset() {
            return filterPreset;
        }

        public String getShape() {
            return shape;
        }

        public boolean isCrowdingSensitive() {
            return crowdingSensitive;
        }

        public String getParticleSizeHint() {
            return particleSizeHint;
        }

        public String getIntensityLevel() {
            return intensityLevel;
        }

        public List<String> getNameHints() {
            return nameHints;
        }

        public String getNotes() {
            return notes;
        }

        public String toString() {
            return displayName;
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
