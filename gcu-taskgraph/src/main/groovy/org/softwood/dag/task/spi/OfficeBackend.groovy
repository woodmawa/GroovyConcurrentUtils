package org.softwood.dag.task.spi

/**
 * The office implementation behind {@link org.softwood.dag.task.XlsxTask}, {@link org.softwood.dag.task.DocxTask} and
 * {@link org.softwood.dag.task.PptxTask} -- GCU 3.0.0.
 *
 * <p>gcu-taskgraph is POI-free: it holds the tasks, the DSLs and the Plan model, never a POI call. The POI-backed
 * adapters live in {@code org.softwood:gcu-office}, which registers its implementation of this interface in
 * {@code META-INF/services/org.softwood.dag.task.spi.OfficeBackend}. {@link OfficeBackends} finds it with
 * {@link ServiceLoader}; with gcu-office absent an office task fails, when it runs, with a message naming the
 * dependency to add -- before 3.0.0 the adapters were here with POI compileOnly, and a consumer without POI got a
 * NoClassDefFoundError from deep inside the first call.</p>
 *
 * <p>Each closure has the adapters' signature: {@code { String action, Map config -> Object }}.</p>
 */
interface OfficeBackend {

    /** The xlsx dispatch closure (XlsxAdapter.asFn() in gcu-office). */
    Closure<Object> xlsxFn()

    /** The docx dispatch closure (DocxAdapter.asFn() in gcu-office). */
    Closure<Object> docxFn()

    /** The pptx dispatch closure (PptxAdapter.asFn() in gcu-office). */
    Closure<Object> pptxFn()
}
