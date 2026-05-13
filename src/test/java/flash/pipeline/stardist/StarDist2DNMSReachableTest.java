package flash.pipeline.stardist;

import de.csbdresden.stardist.StarDist2D;
import de.csbdresden.stardist.StarDist2DNMS;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccess;
import net.imglib2.type.numeric.RealType;
import org.junit.Ignore;
import org.junit.Test;
import org.scijava.ItemIO;
import org.scijava.command.CommandModule;
import org.scijava.plugin.Parameter;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class StarDist2DNMSReachableTest {

    @Test
    public void stardistCommandClassesAndOutputKeysAreReachable() throws Exception {
        assertNotNull(Class.forName("de.csbdresden.stardist.StarDist2D"));
        assertNotNull(Class.forName("de.csbdresden.stardist.StarDist2DNMS"));

        assertOutputParameter(StarDist2D.class, "prob", Dataset.class);
        assertOutputParameter(StarDist2D.class, "dist", Dataset.class);
        assertParameter(StarDist2D.class, "showProbAndDist", boolean.class);
        assertOutputParameter(StarDist2DNMS.class, "label", Dataset.class);
        assertOutputParameter(StarDist2DNMS.class, "polygons",
                de.csbdresden.stardist.Candidates.class);
    }

    @Ignore("GPU/CSBDeep integration test: run manually in Fiji before enabling StarDistFastNms.")
    @Test
    public void stardist2dProbDistOutputsFeedNmsCommand() throws Exception {
        ImageJ ij = new ImageJ();
        try {
            Dataset input = syntheticDataset(ij);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("input", input);
            params.put("modelChoice", "Versatile (fluorescent nuclei)");
            params.put("normalizeInput", Boolean.TRUE);
            params.put("percentileBottom", Double.valueOf(1.0d));
            params.put("percentileTop", Double.valueOf(99.8d));
            params.put("probThresh", Double.valueOf(0.5d));
            params.put("nmsThresh", Double.valueOf(0.3d));
            params.put("outputType", "Label Image");
            params.put("nTiles", Integer.valueOf(1));
            params.put("excludeBoundary", Integer.valueOf(2));
            params.put("roiPosition", "Stack");
            params.put("verbose", Boolean.FALSE);
            params.put("showProbAndDist", Boolean.TRUE);

            CommandModule module = ij.command().run(StarDist2D.class, true, params).get();
            Dataset prob = outputDataset(module, "prob");
            Dataset dist = outputDataset(module, "dist");

            Map<String, Object> nmsParams = new HashMap<String, Object>();
            nmsParams.put("prob", prob);
            nmsParams.put("dist", dist);
            nmsParams.put("probThresh", Double.valueOf(0.5d));
            nmsParams.put("nmsThresh", Double.valueOf(0.3d));
            nmsParams.put("outputType", "Label Image");
            nmsParams.put("excludeBoundary", Integer.valueOf(2));
            nmsParams.put("roiPosition", "Stack");
            nmsParams.put("verbose", Boolean.FALSE);

            CommandModule nmsModule = ij.command()
                    .run(StarDist2DNMS.class, true, nmsParams).get();
            Dataset label = outputDataset(nmsModule, "label");
            assertNotNull(label);
        } finally {
            ij.context().dispose();
        }
    }

    private static void assertOutputParameter(Class<?> commandClass,
                                              String fieldName,
                                              Class<?> fieldType) throws Exception {
        Field field = assertParameter(commandClass, fieldName, fieldType);
        Parameter parameter = field.getAnnotation(Parameter.class);
        assertEquals(ItemIO.OUTPUT, parameter.type());
    }

    private static Field assertParameter(Class<?> commandClass,
                                         String fieldName,
                                         Class<?> fieldType) throws Exception {
        Field field = commandClass.getDeclaredField(fieldName);
        assertEquals(fieldType, field.getType());
        assertNotNull(field.getAnnotation(Parameter.class));
        return field;
    }

    private static Dataset outputDataset(CommandModule module, String key) {
        Object output = module.getOutput(key);
        assertNotNull(output);
        return (Dataset) output;
    }

    private static Dataset syntheticDataset(ImageJ ij) {
        Dataset dataset = ij.dataset().create(new long[] {64L, 64L},
                "synthetic nuclei",
                new AxisType[] {Axes.X, Axes.Y},
                16, false, false);
        paintDisc(dataset, 20, 20, 8);
        paintDisc(dataset, 44, 44, 7);
        dataset.update();
        return dataset;
    }

    private static void paintDisc(Dataset dataset, int cx, int cy, int radius) {
        RandomAccess<RealType<?>> access = dataset.randomAccess();
        int radiusSquared = radius * radius;
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                int dx = x - cx;
                int dy = y - cy;
                if (dx * dx + dy * dy > radiusSquared) {
                    continue;
                }
                access.setPosition(new long[] {x, y});
                access.get().setReal(255.0d);
            }
        }
    }
}
