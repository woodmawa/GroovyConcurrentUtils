package org.softwood.dag.task

import org.softwood.dag.task.docx.DocxAdapter

/**
 * Wires the POI-backed {@link DocxAdapter} into a {@link DocxTask}.
 *
 * <p>Called by {@link TaskFactory} immediately after constructing a
 * {@link DocxTask} so that every factory-created task arrives pre-injected.</p>
 */
class DocxAdapterFactory {

    /**
     * Inject {@link DocxAdapter#asFn()} into {@code task.docxCallFn}.
     *
     * @param task the task to configure
     */
    static void inject(DocxTask task) {
        task.docxCallFn = DocxAdapter.asFn()
    }
}
