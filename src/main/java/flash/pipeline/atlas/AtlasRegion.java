package flash.pipeline.atlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable atlas brain-region metadata used for region autocomplete and CSV metadata. */
public final class AtlasRegion {
    private final String atlasKey;
    private final int id;
    private final String acronym;
    private final String name;
    private final List<Integer> structureIdPath;
    private final List<String> aliases;

    public AtlasRegion(String atlasKey,
                       int id,
                       String acronym,
                       String name,
                       List<Integer> structureIdPath) {
        this(atlasKey, id, acronym, name, structureIdPath, Collections.<String>emptyList());
    }

    public AtlasRegion(String atlasKey,
                       int id,
                       String acronym,
                       String name,
                       List<Integer> structureIdPath,
                       List<String> aliases) {
        this.atlasKey = safe(atlasKey);
        this.id = id;
        this.acronym = safe(acronym);
        this.name = safe(name);
        this.structureIdPath = immutableIntegerList(structureIdPath);
        this.aliases = immutableStringList(aliases);
    }

    public String getAtlasKey() {
        return atlasKey;
    }

    public int getId() {
        return id;
    }

    public String getAcronym() {
        return acronym;
    }

    public String getName() {
        return name;
    }

    public List<Integer> getStructureIdPath() {
        return structureIdPath;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public String displayLabel() {
        if (acronym.isEmpty()) return name;
        if (name.isEmpty()) return acronym;
        return acronym + " - " + name;
    }

    @Override
    public String toString() {
        return displayLabel();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<Integer> immutableIntegerList(List<Integer> values) {
        List<Integer> out = new ArrayList<Integer>();
        if (values != null) {
            for (Integer value : values) {
                if (value != null) {
                    out.add(value);
                }
            }
        }
        return Collections.unmodifiableList(out);
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
}
