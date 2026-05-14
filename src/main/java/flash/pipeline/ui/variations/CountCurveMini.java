package flash.pipeline.ui.variations;

import java.util.OptionalInt;

public class CountCurveMini extends CountCurveStrip {

    public CountCurveMini(double[] xs,
                          double[] ys,
                          OptionalInt stableCountIndex,
                          int[] plateauRange,
                          double yMax) {
        super(xs, ys, stableCountIndex, plateauRange, false, false, yMax,
                miniPreferredSize());
    }

    public void setData(double[] xs,
                        double[] ys,
                        OptionalInt stableCountIndex,
                        int[] plateauRange,
                        double yMax) {
        setDataWithSharedYMax(xs, ys, stableCountIndex, plateauRange, yMax);
    }

    public double yMax() {
        return configuredYMax();
    }

    public double getYMax() {
        return yMax();
    }
}
