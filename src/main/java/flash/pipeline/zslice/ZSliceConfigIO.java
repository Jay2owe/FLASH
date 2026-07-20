package flash.pipeline.zslice;

public final class ZSliceConfigIO {
    private ZSliceConfigIO() {}

    public static String signature(ZSliceConfig config) {
        if (config == null || config.mode == ZSliceMode.FULL || config.selections.isEmpty()) {
            return ZSliceMode.FULL.configToken;
        }
        StringBuilder sb = new StringBuilder(config.mode.configToken);
        for (ZSliceSelection selection : config.orderedSelections()) {
            if (selection == null) continue;
            sb.append('|')
                    .append(selection.seriesIndex)
                    .append(':')
                    .append(selection.range.toToken());
        }
        return sb.toString();
    }
}
