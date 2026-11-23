package org.softwood.dag.task

import org.softwood.dag.task.pptx.PptxAdapter

/**
 * Wires the POI-backed {@link PptxAdapter} into a {@link PptxTask}.
 *
 * <p>Called by {@link TaskFactory} immediately after constructing a
 * {@link PptxTask} so that every factory-created task arrives pre-injected.</p>
 */
class PptxAdapterFactory {

    /**
     * Inject {@link PptxAdapter#asFn()} into {@code task.pptxCallFn}.
     *
     * @param task the task to configure
     */
    static void inject(PptxTask task) {
        task.pptxCallFn = PptxAdapter.asFn()
    }
}
