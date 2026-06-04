package flash.pipeline.ui.variations;

public final class FilterParameterId implements ParameterKey {

    private static final String PREFIX = "filter.";

    private final int sectionIndex;
    private final int entryIndex;
    private final int parameterIndex;
    private final String commandLabel;
    private final String paramKey;
    private final ValueKind valueKind;
    /**
     * Optional human branch name ("dens", "edge", "after combine") shown in the
     * Vary picker and variations grid so the user can tell which image branch a
     * swept parameter is on. DISPLAY-ONLY: deliberately excluded from
     * {@link #stableKey()}/{@link #equals}/{@link #hashCode} so VariationCache
     * disk keys and saved sweeps stay valid. See docs/filter-branch-robustness_COMPLETED.
     */
    private final String branchLabel;

    public FilterParameterId(int sectionIndex,
                             int entryIndex,
                             int parameterIndex,
                             String commandLabel,
                             String paramKey) {
        this(sectionIndex, entryIndex, parameterIndex, commandLabel, paramKey, ValueKind.NUMBER, "");
    }

    public FilterParameterId(int sectionIndex,
                             int entryIndex,
                             int parameterIndex,
                             String commandLabel,
                             String paramKey,
                             ValueKind valueKind) {
        this(sectionIndex, entryIndex, parameterIndex, commandLabel, paramKey, valueKind, "");
    }

    public FilterParameterId(int sectionIndex,
                             int entryIndex,
                             int parameterIndex,
                             String commandLabel,
                             String paramKey,
                             ValueKind valueKind,
                             String branchLabel) {
        if (sectionIndex < 0) {
            throw new IllegalArgumentException("sectionIndex must be >= 0");
        }
        if (entryIndex < 0) {
            throw new IllegalArgumentException("entryIndex must be >= 0");
        }
        if (parameterIndex < 0) {
            throw new IllegalArgumentException("parameterIndex must be >= 0");
        }
        String safeParamKey = paramKey == null ? "" : paramKey.trim();
        if (safeParamKey.isEmpty()) {
            throw new IllegalArgumentException("paramKey must not be empty");
        }
        this.sectionIndex = sectionIndex;
        this.entryIndex = entryIndex;
        this.parameterIndex = parameterIndex;
        this.commandLabel = commandLabel == null ? "" : commandLabel.trim();
        this.paramKey = safeParamKey;
        this.valueKind = valueKind == null ? ValueKind.NUMBER : valueKind;
        this.branchLabel = branchLabel == null ? "" : branchLabel.trim();
    }

    public String branchLabel() {
        return branchLabel;
    }

    public int sectionIndex() {
        return sectionIndex;
    }

    public int getSectionIndex() {
        return sectionIndex;
    }

    public int entryIndex() {
        return entryIndex;
    }

    public int getEntryIndex() {
        return entryIndex;
    }

    public int parameterIndex() {
        return parameterIndex;
    }

    public int getParameterIndex() {
        return parameterIndex;
    }

    public String commandLabel() {
        return commandLabel;
    }

    public String getCommandLabel() {
        return commandLabel;
    }

    public String paramKey() {
        return paramKey;
    }

    public String getParamKey() {
        return paramKey;
    }

    @Override
    public String stableKey() {
        return PREFIX + sectionIndex + "." + entryIndex + "." + parameterIndex + "." + paramKey;
    }

    @Override
    public String displayLabel() {
        if (!branchLabel.isEmpty()) {
            String arrow = " ▸ ";
            if (commandLabel.isEmpty()) {
                return branchLabel + arrow + paramKey;
            }
            return branchLabel + arrow + commandLabel + arrow + paramKey;
        }
        if (commandLabel.isEmpty()) {
            return paramKey;
        }
        return commandLabel + " / " + paramKey;
    }

    @Override
    public ValueKind valueKind() {
        return valueKind;
    }

    public static FilterParameterId parseStableKey(String stableKey) {
        if (stableKey == null) {
            return null;
        }
        String trimmed = stableKey.trim();
        if (!trimmed.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = trimmed.substring(PREFIX.length()).split("\\.", 4);
        if (parts.length != 4) {
            return null;
        }
        try {
            int section = Integer.parseInt(parts[0]);
            int entry = Integer.parseInt(parts[1]);
            int parameter = Integer.parseInt(parts[2]);
            return new FilterParameterId(section, entry, parameter, "", parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FilterParameterId)) return false;
        FilterParameterId other = (FilterParameterId) obj;
        return sectionIndex == other.sectionIndex
                && entryIndex == other.entryIndex
                && parameterIndex == other.parameterIndex
                && paramKey.equals(other.paramKey);
    }

    @Override
    public int hashCode() {
        int result = sectionIndex;
        result = 31 * result + entryIndex;
        result = 31 * result + parameterIndex;
        result = 31 * result + paramKey.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return stableKey();
    }
}
