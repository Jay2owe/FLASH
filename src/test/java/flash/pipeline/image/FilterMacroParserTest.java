package flash.pipeline.image;

import flash.pipeline.image.FilterMacroParser.Op;
import flash.pipeline.image.FilterMacroParser.OpType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class FilterMacroParserTest {

    @Test
    public void parseGaussianBlur() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Gaussian Blur...\", \"sigma=2 stack\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.GAUSSIAN_BLUR, ops.get(0).type);
        assertEquals(2.0, ops.get(0).getParam("sigma"), 0.001);
    }

    @Test
    public void parseSubtractBackground() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Subtract Background...\", \"rolling=20 stack\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.SUBTRACT_BACKGROUND, ops.get(0).type);
        assertEquals(20.0, ops.get(0).getParam("rolling"), 0.001);
    }

    @Test
    public void parseMedian() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Median...\", \"radius=2 stack\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.MEDIAN, ops.get(0).type);
        assertEquals(2.0, ops.get(0).getParam("radius"), 0.001);
    }

    @Test
    public void parseMean() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Mean...\", \"radius=2 stack\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.MEAN, ops.get(0).type);
        assertEquals(2.0, ops.get(0).getParam("radius"), 0.001);
    }

    @Test
    public void parseUnsharpMask() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Unsharp Mask...\", \"radius=10 mask=0.60 stack\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.UNSHARP_MASK, ops.get(0).type);
        assertEquals(10.0, ops.get(0).getParam("radius"), 0.001);
        assertEquals(0.60, ops.get(0).getParam("mask"), 0.001);
    }

    @Test
    public void parseMinimum() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Minimum...\", \"radius=3 stack\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.MINIMUM, ops.get(0).type);
        assertEquals(3.0, ops.get(0).getParam("radius"), 0.001);
    }

    @Test
    public void parseMaximum() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Maximum...\", \"radius=3 stack\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.MAXIMUM, ops.get(0).type);
        assertEquals(3.0, ops.get(0).getParam("radius"), 0.001);
    }

    @Test
    public void parseVariance() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Variance...\", \"radius=5 stack\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.VARIANCE, ops.get(0).type);
        assertEquals(5.0, ops.get(0).getParam("radius"), 0.001);
    }

    @Test
    public void parseDilate() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Dilate\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.DILATE, ops.get(0).type);
    }

    @Test
    public void parseErode() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Erode\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.ERODE, ops.get(0).type);
    }

    @Test
    public void parseOpen() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Open\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.OPEN, ops.get(0).type);
    }

    @Test
    public void parseCloseDoesNotCollideWithCloseDash() {
        // "Close-" must map to CLOSE_, "Close" alone is not a Tier 1 op (it would
        // close the active image — not a filter operation).
        List<Op> closeDash = FilterMacroParser.parseString("run(\"Close-\");");
        assertEquals(1, closeDash.size());
        assertEquals(OpType.CLOSE_, closeDash.get(0).type);

        List<Op> bareClose = FilterMacroParser.parseString("run(\"Close\");");
        assertEquals(1, bareClose.size());
        assertEquals(OpType.UNKNOWN, bareClose.get(0).type);
    }

    @Test
    public void parseFillHoles() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Fill Holes\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.FILL_HOLES, ops.get(0).type);
    }

    @Test
    public void parseSkeletonize() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Skeletonize\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.SKELETONIZE, ops.get(0).type);
    }

    @Test
    public void parseInvert() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Invert\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.INVERT, ops.get(0).type);
    }

    @Test
    public void parseMath_addSubtractMultiplyDivide() {
        List<Op> add  = FilterMacroParser.parseString("run(\"Add...\", \"value=10 stack\");");
        List<Op> sub  = FilterMacroParser.parseString("run(\"Subtract...\", \"value=5 stack\");");
        List<Op> mul  = FilterMacroParser.parseString("run(\"Multiply...\", \"value=2.5 stack\");");
        List<Op> div  = FilterMacroParser.parseString("run(\"Divide...\", \"value=255 stack\");");
        assertEquals(OpType.ADD,      add.get(0).type);
        assertEquals(10.0, add.get(0).getParam("value"), 0.001);
        assertEquals(OpType.SUBTRACT, sub.get(0).type);
        assertEquals(5.0,  sub.get(0).getParam("value"), 0.001);
        assertEquals(OpType.MULTIPLY, mul.get(0).type);
        assertEquals(2.5,  mul.get(0).getParam("value"), 0.001);
        assertEquals(OpType.DIVIDE,   div.get(0).type);
        assertEquals(255.0, div.get(0).getParam("value"), 0.001);
    }

    @Test
    public void parseAutoLocalThreshold() {
        List<Op> ops = FilterMacroParser.parseString(
                "run(\"Auto Local Threshold\", \"method=Bernsen radius=15 parameter_1=0 parameter_2=0 white stack\");");
        assertEquals(1, ops.size());
        Op op = ops.get(0);
        assertEquals(OpType.AUTO_LOCAL_THRESHOLD, op.type);
        assertEquals("Bernsen", op.getStringParam("method"));
        assertEquals(15.0, op.getParam("radius"), 0.001);
        assertEquals(0.0,  op.getParam("parameter_1"), 0.001);
        assertEquals(0.0,  op.getParam("parameter_2"), 0.001);
        assertTrue(op.hasFlag("white"));
        assertTrue(op.hasFlag("stack"));
    }

    @Test
    public void parseBitDepthConversions() {
        List<Op> b8  = FilterMacroParser.parseString("run(\"8-bit\");");
        List<Op> b16 = FilterMacroParser.parseString("run(\"16-bit\");");
        List<Op> b32 = FilterMacroParser.parseString("run(\"32-bit\");");
        assertEquals(OpType.CONVERT_8BIT,  b8.get(0).type);
        assertEquals(OpType.CONVERT_16BIT, b16.get(0).type);
        assertEquals(OpType.CONVERT_32BIT, b32.get(0).type);
    }

    @Test
    public void parseEnhanceContrast() {
        List<Op> ops = FilterMacroParser.parseString(
                "run(\"Enhance Contrast...\", \"saturated=1.0 normalize process_all\");");
        assertEquals(1, ops.size());
        Op op = ops.get(0);
        assertEquals(OpType.ENHANCE_CONTRAST, op.type);
        assertEquals(1.0, op.getParam("saturated"), 0.001);
        assertTrue(op.hasFlag("normalize"));
        assertTrue(op.hasFlag("process_all"));
    }

    @Test
    public void parseGaussianBlur3D() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Gaussian Blur 3D...\", \"x=2 y=2 z=1\");");
        assertEquals(1, ops.size());
        Op op = ops.get(0);
        assertEquals(OpType.GAUSSIAN_BLUR_3D, op.type);
        assertEquals(2.0, op.getParam("x"), 0.001);
        assertEquals(2.0, op.getParam("y"), 0.001);
        assertEquals(1.0, op.getParam("z"), 0.001);
    }

    @Test
    public void parseMedian3DDoesNotCollideWithMedian() {
        List<Op> three = FilterMacroParser.parseString("run(\"Median 3D...\", \"x=2 y=2 z=1\");");
        List<Op> two   = FilterMacroParser.parseString("run(\"Median...\", \"radius=2 stack\");");
        assertEquals(OpType.MEDIAN_3D, three.get(0).type);
        assertEquals(OpType.MEDIAN,    two.get(0).type);
    }

    @Test
    public void parseMinimum3DDoesNotCollideWithMinimum() {
        List<Op> three = FilterMacroParser.parseString("run(\"Minimum 3D...\", \"x=4 y=4 z=1\");");
        List<Op> two   = FilterMacroParser.parseString("run(\"Minimum...\", \"radius=2 stack\");");
        assertEquals(OpType.MINIMUM_3D, three.get(0).type);
        assertEquals(OpType.MINIMUM,    two.get(0).type);
    }

    @Test
    public void parseMultiLineMacro() {
        String macro =
            "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n" +
            "run(\"Subtract Background...\", \"rolling=50 stack\");\n" +
            "run(\"Median...\", \"radius=3 stack\");\n";
        List<Op> ops = FilterMacroParser.parseString(macro);
        assertEquals(3, ops.size());
        assertEquals(OpType.GAUSSIAN_BLUR, ops.get(0).type);
        assertEquals(OpType.SUBTRACT_BACKGROUND, ops.get(1).type);
        assertEquals(OpType.MEDIAN, ops.get(2).type);
    }

    @Test
    public void parseEmptyMacro() {
        List<Op> ops = FilterMacroParser.parseString("");
        assertTrue(ops.isEmpty());
    }

    @Test
    public void parseNullMacro() {
        List<Op> ops = FilterMacroParser.parseString(null);
        assertTrue(ops.isEmpty());
    }

    @Test
    public void parseUnrecognisedCommand() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Custom Plugin...\", \"arg=1\");");
        assertEquals(1, ops.size());
        assertEquals(OpType.UNKNOWN, ops.get(0).type);
    }

    @Test
    public void nonRunControlFlowMarkedUnknown() {
        // imageCalculator, selectImage, close, rename and bare assignments are
        // NOT run() calls — they must not crash the parser, and they must be
        // recorded as UNKNOWN so the caller falls back to the legacy macro path.
        String macro =
            "original = getTitle();\n" +
            "imageCalculator(\"Subtract create stack\", \"a\", \"b\");\n" +
            "selectImage(original);\n" +
            "rename(\"DoG_result\");\n" +
            "close(\"DoG_small\");\n";
        List<Op> ops = FilterMacroParser.parseString(macro);
        assertEquals(5, ops.size());
        for (Op op : ops) assertEquals(OpType.UNKNOWN, op.type);
    }

    @Test
    public void commentsAreSkipped() {
        String macro =
            "// This is a comment\n" +
            "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n" +
            "/* block comment */\n";
        List<Op> ops = FilterMacroParser.parseString(macro);
        assertEquals(1, ops.size());
        assertEquals(OpType.GAUSSIAN_BLUR, ops.get(0).type);
    }

    @Test
    public void getParam_missingKey_returnsNaN() {
        List<Op> ops = FilterMacroParser.parseString("run(\"Gaussian Blur...\", \"sigma=2 stack\");");
        assertTrue(Double.isNaN(ops.get(0).getParam("radius")));
    }

    @Test
    public void getParam_substringKeyDoesNotCollide() {
        // "max" key must not match the "x" inside "max=...". The arg list is
        // "x=2 max=10 z=1" — getParam("x") must return 2, not the 10 inside max.
        List<Op> ops = FilterMacroParser.parseString("run(\"Gaussian Blur 3D...\", \"x=2 max=10 z=1\");");
        Op op = ops.get(0);
        assertEquals(2.0,  op.getParam("x"), 0.001);
        assertEquals(10.0, op.getParam("max"), 0.001);
        assertEquals(1.0,  op.getParam("z"), 0.001);
    }
}
