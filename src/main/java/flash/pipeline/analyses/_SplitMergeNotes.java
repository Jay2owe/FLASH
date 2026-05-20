package flash.pipeline.analyses;

import flash.pipeline.image.ProcessingNotes;

/** Package-private helper to compute macro-compatible processing note strings. */
final class _SplitMergeNotes {
    private _SplitMergeNotes() {}

    static String[] computeProcessingNotes(String[] processMethodPerCh, String[] customMinMaxPerCh,
                                           double[] saturations, int nChannels) {
        String[] out = new String[nChannels];

        for (int i = 0; i < nChannels; i++) {
            String method = (processMethodPerCh != null && i < processMethodPerCh.length)
                    ? processMethodPerCh[i] : "None";
            if (method == null) method = "None";

            switch (method) {
                case "Automatic": {
                    double sat = (saturations != null && i < saturations.length) ? saturations[i] : 0.35;
                    out[i] = ProcessingNotes.automatic(sat);
                    break;
                }
                case "Manual":
                    out[i] = ProcessingNotes.manual();
                    break;
                case "Custom Min-Max Display Ranges": {
                    String val = (customMinMaxPerCh != null && i < customMinMaxPerCh.length)
                            ? customMinMaxPerCh[i] : "None";
                    out[i] = ProcessingNotes.customMinMaxToken(val);
                    break;
                }
                default:
                    out[i] = ProcessingNotes.none();
                    break;
            }
        }

        return out;
    }
}
