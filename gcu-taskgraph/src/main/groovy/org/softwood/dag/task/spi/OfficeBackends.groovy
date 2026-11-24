package org.softwood.dag.task.spi

import groovy.transform.CompileStatic

/**
 * Finds the {@link OfficeBackend} -- GCU 2.1.0. See {@link OfficeBackend} for why it is looked up.
 *
 * <p>The closures handed out resolve the backend when they are CALLED, not when a task is built: building an office
 * task (TaskFactory, a DSL graph) never needs gcu-office, running one does, and the failure then names the
 * dependency instead of escaping as NoClassDefFoundError.</p>
 */
@CompileStatic
final class OfficeBackends {

    static final String MISSING =
        "no office backend on the classpath: office.xlsx / office.docx / office.pptx tasks need " +
        "org.softwood:gcu-office (GCU 2.1.0 moved the POI-backed adapters out of gcu-taskgraph)"

    private static volatile OfficeBackend cached

    private OfficeBackends() {}

    /** The backend, or IllegalStateException naming gcu-office when none is registered. */
    static OfficeBackend get() {
        OfficeBackend b = cached
        if (b != null) return b
        Iterator<OfficeBackend> it = ServiceLoader.load(OfficeBackend, OfficeBackends.classLoader).iterator()
        if (!it.hasNext()) {
            it = ServiceLoader.load(OfficeBackend, Thread.currentThread().contextClassLoader).iterator()
        }
        if (!it.hasNext()) throw new IllegalStateException(MISSING)
        b = it.next()
        cached = b
        return b
    }

    /** True when a backend is registered -- for callers that want to offer office tasks only when they can run. */
    static boolean available() {
        try { get(); return true } catch (IllegalStateException ignored) { return false }
    }

    static Closure<Object> xlsx() { return { String action, Map config -> get().xlsxFn().call(action, config) } }

    static Closure<Object> docx() { return { String action, Map config -> get().docxFn().call(action, config) } }

    static Closure<Object> pptx() { return { String action, Map config -> get().pptxFn().call(action, config) } }
}
