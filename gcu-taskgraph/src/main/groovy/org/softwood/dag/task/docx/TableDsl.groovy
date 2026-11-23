package org.softwood.dag.task.docx

import org.softwood.dag.task.xlsx.StyleDsl
import org.softwood.dag.task.xlsx.StyleSpec

/**
 * DSL builder for a document table.
 *
 * <h3>Usage inside SectionDsl:</h3>
 * <pre>
 *   table {
 *       headers        ['Quarter', 'Revenue', 'Cost', 'Margin']
 *
 *       row            ['Q1', '1.2M', '0.8M', '33%']
 *       row            ['Q2', '1.4M', '0.9M', '36%']
 *
 *       // or bulk-add:
 *       rows           [['Q3','1.6M','1.0M','38%'],
 *                       ['Q4','1.8M','1.1M','39%']]
 *
 *       columnWidths   [1800, 2000, 2000, 1800]  // twips
 *
 *       headerStyle { fill '#1F4E78'; font bold:true, color:'#FFFFFF' }
 *       bodyStyle   { font size:10 }
 *   }
 * </pre>
 */
class TableDsl {

    private List<String>       _headers       = []
    private List<List<String>> _rows          = []
    private boolean            _headerRow     = true
    private StyleSpec          _headerStyle
    private StyleSpec          _bodyStyle
    private List<Integer>      _columnWidths  = []

    // -------------------------------------------------------------------------
    // Headers
    // -------------------------------------------------------------------------

    /** Column header labels. {@code headers ['Name', 'Value', 'Notes']} */
    void headers(List<String> h) { _headers = new ArrayList<>(h) }

    // -------------------------------------------------------------------------
    // Rows
    // -------------------------------------------------------------------------

    /**
     * Append one data row.  Call multiple times for multiple rows.
     * <pre>row ['APAC', '1.2M', '0.8M']</pre>
     */
    void row(List<String> r) { _rows << new ArrayList<>(r) }

    /**
     * Bulk-add multiple rows at once.
     * <pre>rows [['Q1','1.2M'],['Q2','1.4M']]</pre>
     */
    void rows(List<List<String>> r) { r.each { _rows << new ArrayList<>(it) } }

    // -------------------------------------------------------------------------
    // Header row flag
    // -------------------------------------------------------------------------

    /**
     * Whether to render the first row with header styling (default {@code true}).
     * Set {@code false} for data-only tables with no visible header.
     */
    void headerRow(boolean v) { _headerRow = v }

    // -------------------------------------------------------------------------
    // Styles
    // -------------------------------------------------------------------------

    /**
     * Style applied to the header row.
     * <pre>headerStyle { fill '#1F4E78'; font bold:true, color:'#FFFFFF' }</pre>
     */
    void headerStyle(@DelegatesTo(StyleDsl) Closure cl) {
        def dsl = new StyleDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _headerStyle = dsl.build('__table_header__')
    }

    /**
     * Style applied to body (data) rows.
     * <pre>bodyStyle { font size:10; align horizontal:'left' }</pre>
     */
    void bodyStyle(@DelegatesTo(StyleDsl) Closure cl) {
        def dsl = new StyleDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _bodyStyle = dsl.build('__table_body__')
    }

    // -------------------------------------------------------------------------
    // Column widths
    // -------------------------------------------------------------------------

    /**
     * Per-column widths in twips (1/1440 inch).
     * Leave empty to let the adapter auto-size columns.
     * <pre>columnWidths [1800, 2000, 2000, 1800]</pre>
     */
    void columnWidths(List<Integer> w) { _columnWidths = new ArrayList<>(w) }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    TableSpec build() {
        new TableSpec(
            headers     : new ArrayList<>(_headers),
            rows        : _rows.collect { new ArrayList<>(it) },
            headerRow   : _headerRow,
            headerStyle : _headerStyle,
            bodyStyle   : _bodyStyle,
            columnWidths: new ArrayList<>(_columnWidths)
        )
    }
}
