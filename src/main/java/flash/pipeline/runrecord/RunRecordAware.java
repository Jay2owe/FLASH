package flash.pipeline.runrecord;

/**
 * Optional interface for analyses that can emit per-input / per-output / warning
 * events into the active run record. The coordinator sets the context before
 * execution and clears it afterwards, so an analysis never leaks a stale context
 * into the next run.
 *
 * <p>Kept separate from {@code Analysis} so existing test spies and anonymous
 * wrappers keep compiling without implementing run-record hooks.
 */
public interface RunRecordAware {

    /** Called by the coordinator before {@code execute(...)}; cleared (null) afterwards. */
    void setRunRecordContext(AnalysisRunContext context);
}
