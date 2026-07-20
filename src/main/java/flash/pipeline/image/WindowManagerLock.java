package flash.pipeline.image;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Single global lock for all operations that interact with ImageJ's WindowManager.
 * <p>
 * WindowManager is global mutable state — show(), setActivated(), selectWindow(),
 * close(), and macro execution all modify it. Any two concurrent callers will
 * interfere. This lock serialises:
 * <ul>
 *   <li>StarDist3DRunner (needs show() for Z/T hyperstack swap)</li>
 *   <li>PunctaResolveFilter (runs macro with Duplicate/selectWindow/imageCalculator)</li>
 *   <li>FilterExecutor macro fallback (show() + IJ.runMacro)</li>
 * </ul>
 */
public final class WindowManagerLock {
    public static final ReentrantLock LOCK = new ReentrantLock();
    private WindowManagerLock() {}
}
