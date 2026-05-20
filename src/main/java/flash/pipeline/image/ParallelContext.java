package flash.pipeline.image;

public class ParallelContext {
    private static final ThreadLocal<Boolean> inParallel = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() { return Boolean.FALSE; }
    };

    public static void enterParallel() { inParallel.set(Boolean.TRUE); }
    public static void exitParallel() { inParallel.set(Boolean.FALSE); }
    public static boolean isNested() { return inParallel.get(); }
}
