package flash.pipeline.atlas;

import flash.pipeline.ui.wizard.MarkerSearchRanking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** In-memory atlas region index for exact lookup and lightweight typeahead search. */
public final class AtlasRegionLibrary {
    private static final int NO_MATCH = Integer.MAX_VALUE;

    private final String atlasKey;
    private final List<AtlasRegion> regions;
    private final Map<String, AtlasRegion> byAcronym;
    private final Map<String, AtlasRegion> byAlias;
    private final Map<String, AtlasRegion> byName;
    private final Map<Integer, AtlasRegion> byId;

    public AtlasRegionLibrary(String atlasKey, List<AtlasRegion> regions) {
        this.atlasKey = atlasKey == null ? "" : atlasKey.trim();
        this.regions = immutableRegionList(regions);
        this.byAcronym = indexByAcronym(this.regions);
        this.byAlias = indexByAlias(this.regions);
        this.byName = indexByName(this.regions);
        this.byId = indexById(this.regions);
    }

    public String getAtlasKey() {
        return atlasKey;
    }

    public List<AtlasRegion> regions() {
        return regions;
    }

    public AtlasRegion byAcronym(String acronym) {
        return byAcronym.get(normalizeExact(acronym));
    }

    public AtlasRegion byAcronymOrAlias(String value) {
        String key = normalizeExact(value);
        AtlasRegion region = byAcronym.get(key);
        return region == null ? byAlias.get(key) : region;
    }

    public AtlasRegion byName(String name) {
        return byName.get(normalizeExact(name));
    }

    public AtlasRegion byId(int id) {
        return byId.get(Integer.valueOf(id));
    }

    public List<AtlasRegion> search(String query, int topN) {
        if (topN <= 0 || query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String needle = MarkerSearchRanking.normalize(query);
        List<SearchHit> hits = new ArrayList<SearchHit>();
        for (int i = 0; i < regions.size(); i++) {
            AtlasRegion region = regions.get(i);
            int rank = rank(region, needle);
            if (rank < NO_MATCH) {
                hits.add(new SearchHit(region, rank, i));
            }
        }
        Collections.sort(hits, new Comparator<SearchHit>() {
            @Override public int compare(SearchHit left, SearchHit right) {
                if (left.rank != right.rank) return left.rank - right.rank;
                return left.index - right.index;
            }
        });
        List<AtlasRegion> out = new ArrayList<AtlasRegion>();
        for (int i = 0; i < hits.size() && i < topN; i++) {
            out.add(hits.get(i).region);
        }
        return out;
    }

    private static int rank(AtlasRegion region, String query) {
        String acronym = MarkerSearchRanking.normalize(region.getAcronym());
        String name = MarkerSearchRanking.normalize(region.getName());
        List<String> aliases = region.getAliases();

        if (acronym.equals(query) || matchesExact(aliases, query)) return 0;
        if (acronym.startsWith(query) || matchesPrefix(aliases, query)) return 1;
        if (name.startsWith(query)) return 2;
        if (acronym.contains(query) || matchesSubstring(aliases, query)) return 3;
        if (name.contains(query)) return 4;
        if (String.valueOf(region.getId()).startsWith(query)) return 5;
        return NO_MATCH;
    }

    private static boolean matchesExact(List<String> values, String query) {
        if (values == null) return false;
        for (String value : values) {
            if (MarkerSearchRanking.normalize(value).equals(query)) return true;
        }
        return false;
    }

    private static boolean matchesPrefix(List<String> values, String query) {
        if (values == null) return false;
        for (String value : values) {
            if (MarkerSearchRanking.normalize(value).startsWith(query)) return true;
        }
        return false;
    }

    private static boolean matchesSubstring(List<String> values, String query) {
        if (values == null) return false;
        for (String value : values) {
            if (MarkerSearchRanking.normalize(value).contains(query)) return true;
        }
        return false;
    }

    private static List<AtlasRegion> immutableRegionList(List<AtlasRegion> values) {
        List<AtlasRegion> out = new ArrayList<AtlasRegion>();
        if (values != null) {
            for (AtlasRegion value : values) {
                if (value != null) out.add(value);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, AtlasRegion> indexByAcronym(List<AtlasRegion> regions) {
        Map<String, AtlasRegion> out = new LinkedHashMap<String, AtlasRegion>();
        for (AtlasRegion region : regions) {
            String key = normalizeExact(region.getAcronym());
            if (!key.isEmpty() && !out.containsKey(key)) {
                out.put(key, region);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, AtlasRegion> indexByAlias(List<AtlasRegion> regions) {
        Map<String, AtlasRegion> out = new LinkedHashMap<String, AtlasRegion>();
        for (AtlasRegion region : regions) {
            for (String alias : region.getAliases()) {
                String key = normalizeExact(alias);
                if (!key.isEmpty() && !out.containsKey(key)) {
                    out.put(key, region);
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, AtlasRegion> indexByName(List<AtlasRegion> regions) {
        Map<String, AtlasRegion> out = new LinkedHashMap<String, AtlasRegion>();
        for (AtlasRegion region : regions) {
            String key = normalizeExact(region.getName());
            if (!key.isEmpty() && !out.containsKey(key)) {
                out.put(key, region);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<Integer, AtlasRegion> indexById(List<AtlasRegion> regions) {
        Map<Integer, AtlasRegion> out = new LinkedHashMap<Integer, AtlasRegion>();
        for (AtlasRegion region : regions) {
            Integer key = Integer.valueOf(region.getId());
            if (!out.containsKey(key)) {
                out.put(key, region);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static String normalizeExact(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class SearchHit {
        final AtlasRegion region;
        final int rank;
        final int index;

        SearchHit(AtlasRegion region, int rank, int index) {
            this.region = region;
            this.rank = rank;
            this.index = index;
        }
    }
}
