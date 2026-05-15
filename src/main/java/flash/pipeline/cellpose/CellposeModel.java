package flash.pipeline.cellpose;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;

import java.util.Optional;

public enum CellposeModel {
    CYTO3("cyto3",
            "cyto3",
            "Recommended first-pass model for irregular whole-cell bodies and glial soma.",
            true),
    CYTO2("cyto2",
            "cyto2",
            "Legacy whole-cell model that can perform better on older cytoplasmic datasets.",
            true),
    CYTO("cyto",
            "cyto",
            "Older cytoplasm model. Useful mainly for legacy comparisons.",
            true),
    NUCLEI("nuclei",
            "nuclei",
            "Built for rounded nuclear objects rather than irregular whole-cell bodies.",
            false),
    TISSUENET_CP3("tissuenet_cp3",
            "tissuenet_cp3",
            "Broad tissue-trained model. Useful when cyto3 under-segments tissue sections.",
            false),
    LIVECELL_CP3("livecell_cp3",
            "livecell_cp3",
            "Cultured-cell model. Usually less suitable for fixed tissue sections.",
            false);

    private final String token;
    private final String displayName;
    private final String description;
    private final boolean supportsSecondChannel;

    CellposeModel(String token, String displayName, String description, boolean supportsSecondChannel) {
        this.token = token;
        this.displayName = displayName;
        this.description = description;
        this.supportsSecondChannel = supportsSecondChannel;
    }

    public String token() {
        return token;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public boolean supportsSecondChannel() {
        return supportsSecondChannel;
    }

    public String catalogKey() {
        return SegmentationMethod.canonicalCellposeModelKey(token);
    }

    public static CellposeModel fromToken(String token) {
        Optional<CellposeModel> found = fromTokenOptional(token);
        if (found.isPresent()) {
            return found.get();
        }
        String display = token == null || token.trim().isEmpty()
                ? "<missing>"
                : token.trim();
        throw new IllegalArgumentException("Unknown Cellpose model: " + display);
    }

    public static Optional<CellposeModel> fromTokenOptional(String token) {
        if (token != null) {
            String canonical = SegmentationMethod.canonicalCellposeModelKey(token);
            for (CellposeModel model : values()) {
                String trimmed = token.trim();
                if (model.token.equalsIgnoreCase(trimmed)
                        || model.displayName.equalsIgnoreCase(trimmed)
                        || model.catalogKey().equalsIgnoreCase(canonical)) {
                    return Optional.of(model);
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<Boolean> supportsSecondChannelFor(String tokenOrKey) {
        Optional<CellposeModel> found = fromTokenOptional(tokenOrKey);
        return found.isPresent()
                ? Optional.of(Boolean.valueOf(found.get().supportsSecondChannel()))
                : Optional.<Boolean>empty();
    }

    public static String runtimeToken(String tokenOrMethod) {
        if (tokenOrMethod == null || tokenOrMethod.trim().isEmpty()) {
            return CYTO3.token();
        }
        String trimmed = tokenOrMethod.trim();
        if (trimmed.startsWith("cellpose:")) {
            SegmentationMethod method = SegmentationTokenParser.parseLenient(trimmed);
            if (method.isCellpose()) {
                String modelKey = SegmentationMethod.cellposeModelKey(method);
                Optional<CellposeModel> model = fromTokenOptional(modelKey);
                return model.isPresent() ? model.get().token() : modelKey;
            }
        }
        Optional<CellposeModel> model = fromTokenOptional(trimmed);
        if (model.isPresent()) return model.get().token();
        return trimmed;
    }

    public static String[] displayNames() {
        CellposeModel[] values = values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].displayName;
        }
        return names;
    }
}
