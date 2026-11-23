package org.softwood.dag.task.xlsx

/**
 * DSL builder for a single spreadsheet row.
 *
 * <h3>Usage patterns inside SheetDsl:</h3>
 * <pre>
 *   // Named args shorthand — no closure required
 *   row 1, style: 'header', height: 24, values: ['Region', 'Revenue', 'Cost']
 *
 *   // Full closure form
 *   row(2) {
 *       cell 'A', value: 'APAC', style: 'bold'
 *       cell 'B', value: 1_200_000, style: 'money'
 *       cell('C') {
 *           formula 'B2 * 0.85'
 *           style   'money'
 *           comment 'After 15% discount'
 *       }
 *   }
 *
 *   // Anonymous rows — auto-sequenced
 *   row { cell 'Hello'; cell 42 }
 *   row { cell 'World'; cell 99 }
 *
 *   // Skip a row
 *   row()
 *
 *   // Inline value list (shorthand equivalent to values: [...])
 *   row 5, values: [data.name, data.score, data.grade]
 * </pre>
 *
 * <p>Column auto-sequencing starts at 1 and increments for each cell that has
 * no explicit ref.  Cells with an explicit ref do not advance the counter.</p>
 */
class RowDsl {

    private Integer         _rowIndex
    private List<CellSpec>  _cells    = []
    private String          _styleRef
    private float           _height   = 0f
    private boolean         _hidden   = false
    private int             _nextCol  = 1   // auto-column sequencer

    // -------------------------------------------------------------------------
    // Constructor helpers
    // -------------------------------------------------------------------------

    RowDsl() {}

    /** Apply named-arg options from the shorthand form: {@code row 3, style:'x', height:20} */
    void applyOptions(Map<String, Object> opts) {
        if (opts?.style  != null) _styleRef = opts.style as String
        if (opts?.height != null) _height   = opts.height as float
        if (opts?.hidden != null) _hidden   = opts.hidden as boolean
        if (opts?.values) {
            // 2.0.1: add each as a value cell. cell(v) dispatched a String to cell(String ref), so every text value
            // was read as a column reference ('name' -> column -1) and the adapter failed.
            (opts.values as List).each { Object v -> _cells << new CellSpec(colIndex: _nextCol++, value: v, styleRef: _styleRef) }
        }
    }

    // -------------------------------------------------------------------------
    // Row-level properties
    // -------------------------------------------------------------------------

    void style(String name) { _styleRef = name }
    void height(float h)    { _height   = h }
    void height(Number h)   { _height   = h.floatValue() }
    void hidden(boolean h = true) { _hidden = h }

    // -------------------------------------------------------------------------
    // cell() — full closure form
    // -------------------------------------------------------------------------

    /**
     * Full-featured cell with ref + closure.
     * <pre>
     *   cell('B') {
     *       value  42
     *       style  'money'
     *       comment 'See footnote'
     *   }
     * </pre>
     */
    CellSpec cell(String ref, @DelegatesTo(CellDsl) Closure cl) {
        def dsl = new CellDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        def spec = dsl.build(ref, null)
        _cells << spec
        spec
    }

    /**
     * Map-options cell shorthand.
     * <pre>
     *   cell 'C', value: 'Total', style: 'bold'
     *   cell 'D', formula: 'SUM(D2:D10)'
     * </pre>
     */
    /** Named-argument form, {@code cell 'B3', value:1}: Groovy passes named arguments first (2.0.1). */
    CellSpec cell(Map<String, Object> opts, String ref) { cell(ref, opts) }

    CellSpec cell(String ref, Map<String, Object> opts = [:]) {
        def dsl = new CellDsl()
        _applyMapOpts(dsl, opts)
        def spec = dsl.build(ref, null)
        _cells << spec
        spec
    }

    /** Auto-sequenced cell with closure — column letter derived from position. */
    CellSpec cell(@DelegatesTo(CellDsl) Closure cl) {
        def dsl = new CellDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        def spec = dsl.build(null, _nextCol++)
        _cells << spec
        spec
    }

    // -------------------------------------------------------------------------
    // cell() — value shorthand
    // -------------------------------------------------------------------------

    /** Raw value shorthand — auto-sequenced column. {@code cell 42} */
    CellSpec cell(Object value) {
        def spec = new CellSpec(colIndex: _nextCol++, value: value, styleRef: _styleRef)
        _cells << spec
        spec
    }

    /** Empty cell placeholder — advances column counter. */
    void cell() {
        _cells << new CellSpec(colIndex: _nextCol++)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void _applyMapOpts(CellDsl dsl, Map<String, Object> opts) {
        if (!opts) return
        if (opts.value    != null) dsl.value(opts.value)
        if (opts.formula  != null) dsl.formula(opts.formula as String)
        if (opts.style    != null) dsl.style(opts.style as String)
        if (opts.link     != null) dsl.link(opts.link as String)
        if (opts.comment  != null) dsl.comment(opts.comment as String)
        if (opts.colspan  != null) dsl.colspan(opts.colspan as int)
        if (opts.rowspan  != null) dsl.rowspan(opts.rowspan as int)
        if (opts.name     != null) dsl.name(opts.name as String)
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    RowSpec build(Integer index) {
        new RowSpec(
            rowIndex : index ?: _rowIndex,
            cells    : _cells,
            styleRef : _styleRef,
            height   : _height,
            hidden   : _hidden
        )
    }

    RowSpec build() { build(null) }
}
