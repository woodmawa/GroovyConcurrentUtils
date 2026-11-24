package org.softwood.dag.task.office

import groovy.transform.CompileStatic
import org.softwood.dag.task.docx.DocxAdapter
import org.softwood.dag.task.pptx.PptxAdapter
import org.softwood.dag.task.spi.OfficeBackend
import org.softwood.dag.task.xlsx.XlsxAdapter

/**
 * gcu-office's {@link OfficeBackend} -- GCU 3.0.0. Registered in
 * {@code META-INF/services/org.softwood.dag.task.spi.OfficeBackend}, so gcu-taskgraph's TaskFactory wires its office
 * tasks to these adapters whenever gcu-office is on the classpath.
 */
@CompileStatic
class PoiOfficeBackend implements OfficeBackend {

    @Override Closure<Object> xlsxFn() { XlsxAdapter.asFn() }

    @Override Closure<Object> docxFn() { DocxAdapter.asFn() }

    @Override Closure<Object> pptxFn() { PptxAdapter.asFn() }
}
