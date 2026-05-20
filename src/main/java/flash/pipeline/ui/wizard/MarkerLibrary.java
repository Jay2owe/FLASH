package flash.pipeline.ui.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Marker metadata used by setup helpers.
 */
public final class MarkerLibrary {

    public static final Entry AUTOFLUORESCENCE = new Entry(
            "autofluorescence",
            "Autofluorescence channel (no target)",
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            "special",
            "diffuse",
            false,
            false);

    public static final Entry OTHER_CUSTOM = new Entry(
            "other_custom",
            "Other / custom",
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            "special",
            "",
            false,
            false);

    private final List<Entry> entries;

    public MarkerLibrary(List<Entry> entries) {
        // OTHER_CUSTOM is intentionally NOT appended any more: custom names are accepted
        // straight from the typeahead's text field, so the explicit "Other / custom" choice
        // would only get in the way. The constant remains exported for legacy callers
        // that still reference it (e.g. headless tests).
        List<Entry> copy = new ArrayList<Entry>();
        if (entries != null) {
            copy.addAll(entries);
        }
        copy.add(AUTOFLUORESCENCE);
        this.entries = Collections.unmodifiableList(copy);
    }

    public List<Entry> entries() {
        return entries;
    }

    public static MarkerLibrary seedLibrary() {
        return new MarkerLibrary(Arrays.asList(
                entry("dapi", "DAPI", "nuclear", "round", false, false, "Hoechst", "nuclei", "DNA"),
                entry("iba1", "IBA1", "microglia", "complex", true, true, "Iba-1", "AIF1", "microglia"),
                entry("gfap", "GFAP", "astrocyte", "complex", true, true, "astrocyte", "glial fibrillary acidic protein"),
                entry("p2ry12", "P2RY12", "microglia", "complex", true, true, "P2Y12", "P2Y12 receptor"),
                entry("tmEM119", "TMEM119", "microglia", "complex", true, true, "transmembrane protein 119"),
                entry("cd68", "CD68", "lysosome", "puncta_like", false, false, "ED1", "phagolysosome"),
                entry("abeta", "Amyloid beta", "pathology", "puncta_like", false, false, "Abeta", "A beta", "6E10", "4G8"),
                entry("thioflavin", "Thioflavin S", "pathology", "puncta_like", false, false, "ThioS", "plaques"),
                entry("map2", "MAP2", "neuronal", "complex", true, true, "dendrite", "microtubule associated protein 2"),
                entry("neun", "NeuN", "neuronal", "round", true, false, "RBFOX3", "neuronal nuclei"),
                entry("psd95", "PSD95", "synapse", "puncta_like", false, false, "DLG4", "postsynaptic density"),
                entry("synaptophysin", "Synaptophysin", "synapse", "puncta_like", false, false, "SYP", "presynaptic"),
                entry("mbp", "MBP", "myelin", "diffuse", false, false, "myelin basic protein"),
                entry("olig2", "OLIG2", "oligodendrocyte", "round", true, false, "oligodendrocyte transcription factor"),
                entry("cc1", "CC1", "oligodendrocyte", "round", true, false, "APC", "adenomatous polyposis coli"),
                entry("lectin", "Lectin", "vascular", "complex", true, true, "IB4", "vasculature"),
                entry("cd31", "CD31", "vascular", "complex", true, true, "PECAM1", "endothelial"),
                entry("ki67", "Ki67", "proliferation", "round", true, false, "MKI67"),
                entry("cleaved_caspase3", "Cleaved caspase-3", "apoptosis", "puncta_like", false, false, "CC3", "cCasp3"),
                entry("tom20", "TOM20", "mitochondria", "puncta_like", false, false, "TOMM20", "mitochondria")
        ));
    }

    private static Entry entry(String id,
                               String displayName,
                               String category,
                               String shape,
                               boolean crowdingSensitive,
                               boolean crowdedByDefault,
                               String... aliasesAndHints) {
        List<String> aliases = new ArrayList<String>();
        List<String> hints = new ArrayList<String>();
        for (int i = 0; i < aliasesAndHints.length; i++) {
            if (i < Math.max(1, aliasesAndHints.length / 2)) {
                aliases.add(aliasesAndHints[i]);
            } else {
                hints.add(aliasesAndHints[i]);
            }
        }
        return new Entry(id, displayName, aliases, hints, category, shape, crowdingSensitive, crowdedByDefault);
    }

    public static final class Entry {
        private final String id;
        private final String displayName;
        private final List<String> aliases;
        private final List<String> nameHints;
        private final String category;
        private final List<String> additionalCategories;
        private final String shape;
        private final boolean crowdingSensitive;
        private final boolean crowdedByDefault;

        public Entry(String id,
                     String displayName,
                     List<String> aliases,
                     List<String> nameHints,
                     String category,
                     String shape,
                     boolean crowdingSensitive,
                     boolean crowdedByDefault) {
            this(id, displayName, aliases, nameHints, category,
                    Collections.<String>emptyList(), shape, crowdingSensitive, crowdedByDefault);
        }

        public Entry(String id,
                     String displayName,
                     List<String> aliases,
                     List<String> nameHints,
                     String category,
                     List<String> additionalCategories,
                     String shape,
                     boolean crowdingSensitive,
                     boolean crowdedByDefault) {
            this.id = id == null ? "" : id;
            this.displayName = displayName == null ? "" : displayName;
            this.aliases = aliases == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(aliases));
            this.nameHints = nameHints == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(nameHints));
            this.category = category == null ? "" : category;
            this.additionalCategories = additionalCategories == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(additionalCategories));
            this.shape = shape == null ? "" : shape;
            this.crowdingSensitive = crowdingSensitive;
            this.crowdedByDefault = crowdedByDefault;
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

        public List<String> getNameHints() {
            return nameHints;
        }

        public String getCategory() {
            return category;
        }

        public List<String> getAdditionalCategories() {
            return additionalCategories;
        }

        public List<String> getAllCategories() {
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<String>();
            if (!category.isEmpty()) all.add(category);
            for (String c : additionalCategories) {
                if (c != null && !c.isEmpty()) all.add(c);
            }
            return Collections.unmodifiableList(new ArrayList<String>(all));
        }

        public String getShape() {
            return shape;
        }

        public boolean isCrowdingSensitive() {
            return crowdingSensitive;
        }

        public boolean isCrowdedByDefault() {
            return crowdedByDefault;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
