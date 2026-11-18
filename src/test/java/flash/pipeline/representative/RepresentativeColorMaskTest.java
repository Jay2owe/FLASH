package flash.pipeline.representative;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertArrayEquals;

public class RepresentativeColorMaskTest {

    @Test
    public void unknownColourNamesRenderGreyNotBlack() throws Exception {
        assertArrayEquals(new boolean[] { true, true, true }, mask("Fire"));
        assertArrayEquals(new boolean[] { true, true, true }, mask("Grays"));
        assertArrayEquals(new boolean[] { true, false, true }, mask("Magenta"));
        assertArrayEquals(new boolean[] { true, true, false }, mask("#FFC000"));
    }

    private static boolean[] mask(String name) throws Exception {
        Class<?> type = Class.forName(
                "flash.pipeline.representative.RepresentativePreviewRenderer$ColorMask");
        Method from = type.getDeclaredMethod("from", String.class);
        from.setAccessible(true);
        Object mask = from.invoke(null, name);
        return new boolean[] { flag(type, mask, "red"), flag(type, mask, "green"),
                flag(type, mask, "blue") };
    }

    private static boolean flag(Class<?> type, Object mask, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(mask);
    }
}
