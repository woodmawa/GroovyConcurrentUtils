package org.softwood.dag.task

import org.softwood.dag.task.xlsx.XlsxAdapter

/**
 * Wires the POI-backed {@link XlsxAdapter} into an {@link XlsxTask}.
 *
 * <p>Called by {@link TaskFactory} immediately after constructing an
 * {@link XlsxTask} so that every factory-created task arrives pre-injected
 * and ready to execute without additional wiring by the caller.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   XlsxTask task = TaskFactory.createXlsxTask(id, name, ctx)
 *   // xlsxCallFn is already set — no extra step needed.
 * </pre>
 */
class XlsxAdapterFactory {

    /**
     * Inject {@link XlsxAdapter#asFn()} into {@code task.xlsxCallFn}.
     *
     * @param task the task to configure
     */
    static void inject(XlsxTask task) {
        task.xlsxCallFn = XlsxAdapter.asFn()
    }
}
