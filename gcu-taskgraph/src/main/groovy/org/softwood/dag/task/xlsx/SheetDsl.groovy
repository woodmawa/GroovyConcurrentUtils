package org.softwood.dag.task.xlsx

/**
 * DSL builder for a single worksheet.
 *
 * <h3>Usage inside XlsxWorkbookDsl:</h3>
 * <pre>
 *   sheet('Summary') {
 *       tabColor    '#90CAF9'
 *       freeze      'B2'            // or freeze(1, 1) numeric form
 *       autoFilter  'A1:H1'
 *       protect     password:'secret', sort:true, filter:true
 *       lock()                      // no-password lock
 *       page        paper:'A4', orientation:'landscape', fitWidth:1
 *
 *       defaultRowHeight   18
 *       defaultColumnWidth 14
 *       defaultStyle       'body'
 *
 *       style('localOverride') { font bold:true }
 *
 *       namedRange 'Revenue', 'B2:B100'
 *
 *       row 1, style:'header', height:24, values:['A','B','C']
 *       row(2) { cell 'A', value:'x'; cell 'B', value:42 }
 *       row()                       // skip row
 *       rows dataKey:'myData', startAt:3, style:'body'
 *
 *       cell 'A105', value:'Footer', style:'footer'
 *       cell('B1') { formula 'SUM(B2:B100)' }
 *
 *       column 'A', width:20
 *       column('B:D') { width 14; style 'money' }
 *
 *       range 'A1:H1', style:'header'
 *       range('B2:D100') { style 'money'; zebraStripe true }
 *
 *       merge  'A105:H105'
 *       groupRows    2..50,   collapsed:true
 *       groupColumns 'C:F',   collapsed:false
 *   }
 * </pre>
 *
 * <p>Auto row-sequencing: {@code _nextRow} starts at 1.
 * {@code row(n,...)} sets index=n and advances to n+1.
 * {@code row { }} uses {@code _nextRow} then increments.
 * {@code row()} skips one row (increments only).
 * {@code rows(dataKey:...)} records a {@link DataRowsSpec} at current {@code _nextRow} but does NOT advance.</p>
 */
class SheetDsl {

    private final String               _name
    private       String               _tabColor
    private       String               _freezeRef
    private       String               _autoFilterRef
    private       String               _password
    private       boolean              _locked            = false
    private final Map<String, Object>  _pageSettings      = [:]
    private       float                _defaultRowHeight  = 0f
    private       int                  _defaultColumnWidth = 0
    private       String               _defaultStyle
    private final Map<String, StyleSpec>  _localStyles    = [:]
    private final Map<String, String>  _namedRanges       = [:]
    private final List<RowSpec>        _rows              = []
    private final List<DataRowsSpec>   _deferredRows      = []
    private final List<ColumnSpec>     _columns           = []
    private final List<RangeSpec>      _ranges            = []
    private final List<MergeSpec>      _merges            = []
    private final List<GroupSpec>      _rowGroups         = []
    private final List<GroupSpec>      _columnGroups      = []
    private       int                  _nextRow           = 1

    SheetDsl(String name) { _name = name }

    // -------------------------------------------------------------------------
    // Sheet metadata
    // -------------------------------------------------------------------------

    void tabColor(String color) { _tabColor = color }

    /** Freeze pane using A1-notation top-left of the unfrozen region, e.g. {@code freeze 'B2'}. */
    void freeze(String ref) { _freezeRef = ref }

    /**
     * Freeze pane by row/column count.
     * {@code freeze 1, 1} freezes the first row and first column, so unfrozen pane starts at B2.
     */
    void freeze(int rows, int cols) {
        String col = cols > 0 ? _colLetter(cols + 1) : 'A'
        String row = rows > 0 ? String.valueOf(rows + 1) : '1'
        _freezeRef = "${col}${row}"
    }

    void autoFilter(String ref) { _autoFilterRef = ref }

    /**
     * Enable sheet protection.
     * <pre>protect password:'secret', sort:true, filter:true</pre>
     * All extra keys (sort, filter, selectLockedCells, etc.) are stored in
     * {@code pageSettings.protectionOptions} for the adapter to consume.
     */
    void protect(Map<String, Object> args) {
        _password = args?.password as String
        _locked   = true
        def extra = args?.findAll { k, v -> k != 'password' }
        if (extra) _pageSettings['protectionOptions'] = extra
    }

    /** Lock sheet without a password. */
    void lock() { _locked = true }

    /**
     * Page-setup options.
     * <pre>page paper:'A4', orientation:'landscape', fitWidth:1, fitHeight:0</pre>
     */
    void page(Map<String, Object> args) {
        if (args) _pageSettings.putAll(args)
    }

    void defaultRowHeight(Number h)    { _defaultRowHeight    = h.floatValue() }
    void defaultColumnWidth(Number w)  { _defaultColumnWidth  = w.intValue()   }
    void defaultStyle(String s)        { _defaultStyle = s }

    // -------------------------------------------------------------------------
    // Local style overrides
    // -------------------------------------------------------------------------

    /**
     * Define a style scoped to this sheet only.
     * <pre>style('localOverride') { font bold:true }</pre>
     * Sheet-local styles are stored in {@code pageSettings.localStyles} and take
     * precedence over same-named workbook styles in the adapter.
     */
    void style(String name, @DelegatesTo(StyleDsl) Closure cl) {
        def dsl = new StyleDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _localStyles[name] = dsl.build(name)
    }

    // -------------------------------------------------------------------------
    // Named ranges (sheet-scope)
    // -------------------------------------------------------------------------

    void namedRange(String name, String ref) { _namedRanges[name] = ref }

    // -------------------------------------------------------------------------
    // Rows
    // -------------------------------------------------------------------------

    /**
     * Row at an explicit index with named options.
     * <pre>row 1, style:'header', height:24, values:['A','B','C']</pre>
     */
    /** Named-argument form, {@code row 1, values:[...]}: Groovy passes named arguments first (2.0.1). */
    void row(Map<String, Object> opts, int index) { row(index, opts) }

    void row(int index, Map<String, Object> opts) {
        def dsl = new RowDsl()
        dsl.applyOptions(opts)
        _rows << dsl.build(index)
        _nextRow = index + 1
    }

    /** Empty row at an explicit index — no cells, no options. */
    void row(int index) {
        _rows << new RowSpec(rowIndex: index)
        _nextRow = index + 1
    }

    /**
     * Row at an explicit index with a closure.
     * <pre>row(2) { cell 'A', value:'x'; cell 'B', value:42 }</pre>
     */
    void row(int index, @DelegatesTo(RowDsl) Closure cl) {
        def dsl = new RowDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _rows << dsl.build(index)
        _nextRow = index + 1
    }

    /**
     * Auto-sequenced row — uses and increments {@code _nextRow}.
     * <pre>row { cell 'A', value:'x'; cell 'B', value:42 }</pre>
     */
    void row(@DelegatesTo(RowDsl) Closure cl) {
        def dsl = new RowDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _rows << dsl.build(_nextRow++)
    }

    /** Skip one row — increments {@code _nextRow} without creating a {@link RowSpec}. */
    void row() { _nextRow++ }

    /**
     * Deferred data rows from WorkingMemory — does NOT advance {@code _nextRow}.
     * <pre>rows dataKey:'myData', startAt:3, style:'body'</pre>
     */
    void rows(Map<String, Object> opts) {
        _deferredRows << new DataRowsSpec(
            startAt : (opts.startAt as Integer) ?: _nextRow,
            dataKey : opts.dataKey as String,
            styleRef: opts.style   as String
        )
    }

    // -------------------------------------------------------------------------
    // Standalone cells (creates a single-cell RowSpec at the cell's row index)
    // -------------------------------------------------------------------------

    /**
     * Standalone cell at an absolute ref.
     * <pre>cell 'A105', value:'Footer', style:'footer'</pre>
     */
    /** Named-argument form, {@code cell 'B3', value:1} (2.0.1). */
    void cell(Map<String, Object> opts, String ref) { cell(ref, opts) }

    void cell(String ref, Map<String, Object> opts = [:]) {
        def dsl = new CellDsl()
        if (opts.value   != null) dsl.value(opts.value)
        if (opts.formula != null) dsl.formula(opts.formula as String)
        if (opts.style   != null) dsl.style(opts.style as String)
        if (opts.link    != null) dsl.link(opts.link as String)
        _rows << new RowSpec(rowIndex: _rowIndexFromRef(ref), cells: [dsl.build(ref, null)])
    }

    /**
     * Standalone cell with full closure.
     * <pre>cell('B1') { formula 'SUM(B2:B100)' }</pre>
     */
    void cell(String ref, @DelegatesTo(CellDsl) Closure cl) {
        def dsl = new CellDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _rows << new RowSpec(rowIndex: _rowIndexFromRef(ref), cells: [dsl.build(ref, null)])
    }

    // -------------------------------------------------------------------------
    // Columns
    // -------------------------------------------------------------------------

    /**
     * Column spec with named options.
     * <pre>column 'A', width:20</pre>
     */
    /** Named-argument form, {@code column 'A', width:12} (2.0.1). */
    void column(Map<String, Object> opts, String ref) { column(ref, opts) }

    void column(String ref, Map<String, Object> opts) {
        def dsl = new ColumnDsl()
        if (opts.width    != null) dsl.width(opts.width as Number)
        if (opts.hidden   != null) dsl.hidden(opts.hidden as boolean)
        if (opts.autoSize != null) dsl.autoSize(opts.autoSize as boolean)
        if (opts.style    != null) dsl.style(opts.style as String)
        _columns << dsl.build(ref)
    }

    /** Column ref with no options — records ref only. */
    void column(String ref) {
        _columns << new ColumnSpec(ref: ref)
    }

    /**
     * Column spec with full closure.
     * <pre>column('B:D') { width 14; style 'money' }</pre>
     */
    void column(String ref, @DelegatesTo(ColumnDsl) Closure cl) {
        def dsl = new ColumnDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _columns << dsl.build(ref)
    }

    // -------------------------------------------------------------------------
    // Ranges
    // -------------------------------------------------------------------------

    /**
     * Range operation with named options.
     * <pre>range 'A1:H1', style:'header'</pre>
     */
    /** Named-argument form, {@code range 'A1:C3', style:'x'} (2.0.1). */
    void range(Map<String, Object> opts, String ref) { range(ref, opts) }

    void range(String ref, Map<String, Object> opts) {
        def dsl = new RangeDsl()
        if (opts.style       != null) dsl.style(opts.style as String)
        if (opts.borders     != null) dsl.borders(opts.borders as String)
        if (opts.zebraStripe != null) dsl.zebraStripe(opts.zebraStripe as boolean)
        if (opts.comment     != null) dsl.comment(opts.comment as String)
        _ranges << dsl.build(ref)
    }

    /** Range ref with no options. */
    void range(String ref) {
        _ranges << new RangeSpec(ref: ref)
    }

    /**
     * Range operation with full closure.
     * <pre>range('B2:D100') { style 'money'; zebraStripe true }</pre>
     */
    void range(String ref, @DelegatesTo(RangeDsl) Closure cl) {
        def dsl = new RangeDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _ranges << dsl.build(ref)
    }

    // -------------------------------------------------------------------------
    // Structural
    // -------------------------------------------------------------------------

    void merge(String ref) { _merges << new MergeSpec(ref: ref) }

    /**
     * Row grouping (outline).
     * Named args go first in Groovy: {@code groupRows 2..50, collapsed:true}
     * dispatches as {@code groupRows([collapsed:true], 2..50)}.
     */
    void groupRows(Map<String, Object> opts, IntRange rowRange) {
        _rowGroups << new GroupSpec(
            range    : rowRange,
            collapsed: opts?.collapsed as boolean ?: false
        )
    }

    /** Row grouping without options. */
    void groupRows(IntRange rowRange) { groupRows([:], rowRange) }

    /**
     * Column grouping (outline).
     * <pre>groupColumns 'C:F', collapsed:false</pre>
     */
    void groupColumns(Map<String, Object> opts, String ref) {
        _columnGroups << new GroupSpec(
            range    : ref,
            collapsed: opts?.collapsed as boolean ?: false
        )
    }

    /** Column grouping without options. */
    void groupColumns(String ref) { groupColumns([:], ref) }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    SheetPlan build() {
        // Sheet-local styles are folded into pageSettings so they travel with the plan.
        Map<String, Object> settings = new LinkedHashMap<>(_pageSettings)
        if (_localStyles) settings['localStyles'] = new LinkedHashMap<>(_localStyles)

        new SheetPlan(
            name               : _name,
            rows               : new ArrayList<>(_rows),
            deferredRows       : new ArrayList<>(_deferredRows),
            columns            : new ArrayList<>(_columns),
            ranges             : new ArrayList<>(_ranges),
            merges             : new ArrayList<>(_merges),
            rowGroups          : new ArrayList<>(_rowGroups),
            columnGroups       : new ArrayList<>(_columnGroups),
            freezeRef          : _freezeRef,
            autoFilterRef      : _autoFilterRef,
            tabColor           : _tabColor,
            password           : _password,
            locked             : _locked,
            pageSettings       : settings,
            defaultRowHeight   : _defaultRowHeight,
            defaultColumnWidth : _defaultColumnWidth,
            defaultStyle       : _defaultStyle,
            namedRanges        : new LinkedHashMap<>(_namedRanges)
        )
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Convert a 1-based column index to its letter(s): 1→A, 26→Z, 27→AA. */
    private static String _colLetter(int colIndex) {
        def result = new StringBuilder()
        int n = colIndex
        while (n > 0) {
            n--
            result.insert(0, (char) ('A' + n % 26))
            n = (int) (n / 26)
        }
        result.toString()
    }

    /** Extract a 1-based row number from an A1-notation ref: 'A105' → 105. */
    private static int _rowIndexFromRef(String ref) {
        def m = (ref =~ /(\d+)/)
        m ? (m[0][1] as int) : 1
    }
}
