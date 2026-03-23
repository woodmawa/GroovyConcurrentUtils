package org.softwood.dag.task.xlsx

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.XlsxTask

/**
 * Fluent DSL builder for a complete workbook operation.
 *
 * <h3>Usage in XlsxTask:</h3>
 * <pre>
 *   xlsxTask('generate-report') {
 *       file '/reports/monthly.xlsx'
 *       workbook {
 *           createIfMissing        true
 *           preserveExistingStyles false
 *           evaluateFormulas       false
 *           template               '/templates/corp.xlsx'
 *
 *           namedRange 'TotalRevenue', 'Summary!B2:B500'
 *
 *           style('header') {
 *               font  bold:true, color:'#FFFFFF'
 *               fill  '#1F4E78'
 *               align horizontal:'center'
 *           }
 *           style('money') {
 *               dataFormat '#,##0.00'
 *               align horizontal:'right'
 *           }
 *
 *           sheet('Summary') {
 *               row 1, style:'header', values:['Region','Revenue','Cost']
 *               rows dataKey:'summaryData', startAt:2, style:'body'
 *           }
 *           sheet('Detail') {
 *               rows dataKey:'detailData', startAt:1, style:'body'
 *           }
 *       }
 *   }
 * </pre>
 *
 * <p>The {@code workbook { }} closure sets {@code action = SCRIPT} on the owner task.</p>
 *
 * @see WorkbookPlan
 * @see SheetDsl
 */
class XlsxWorkbookDsl {

    /** TaskContext passed through for resolver-aware field support. */
    private final TaskContext ctx
    /** Previous task result, forwarded to deferred config closures. */
    private final Object      prevValue
    /** Owning task — available to nested DSL closures if needed. */
    private final XlsxTask    owner

    private       boolean                _createIfMissing        = true
    private       boolean                _preserveExistingStyles = false
    private       boolean                _evaluateFormulas       = false
    private       String                 _templatePath
    private final Map<String, StyleSpec>  _styles                = [:]
    private final List<SheetDsl>          _sheetDsls             = []
    private final Map<String, String>     _namedRanges           = [:]

    XlsxWorkbookDsl(TaskContext ctx, Object prevValue, XlsxTask owner) {
        this.ctx       = ctx
        this.prevValue = prevValue
        this.owner     = owner
    }

    // -------------------------------------------------------------------------
    // Workbook-level options
    // -------------------------------------------------------------------------

    /**
     * Create the file if it does not exist (default {@code true}).
     * Pass {@code false} to fail fast when the file is absent.
     */
    void createIfMissing(boolean v) { _createIfMissing = v }

    /**
     * When {@code true}, the adapter will not overwrite existing styles with
     * same-named entries from the DSL (default {@code false}).
     */
    void preserveExistingStyles(boolean v) { _preserveExistingStyles = v }

    /**
     * Trigger formula recalculation after writing (default {@code false}).
     * Only meaningful for write/transform actions.
     */
    void evaluateFormulas(boolean v) { _evaluateFormulas = v }

    /**
     * Base template workbook to copy before applying changes.
     * <pre>template '/templates/corp.xlsx'</pre>
     */
    void template(String path) { _templatePath = path }

    // -------------------------------------------------------------------------
    // Workbook-scope named ranges
    // -------------------------------------------------------------------------

    /**
     * Declare a workbook-scope named range.
     * <pre>namedRange 'TotalRevenue', 'Summary!B2:B500'</pre>
     */
    void namedRange(String name, String ref) { _namedRanges[name] = ref }

    // -------------------------------------------------------------------------
    // Styles
    // -------------------------------------------------------------------------

    /**
     * Define a reusable named style.
     * <pre>
     *   style('header') {
     *       font  bold:true, size:12, color:'#FFFFFF'
     *       fill  '#1F4E78'
     *       align horizontal:'center', vertical:'middle'
     *       border all:'thin'
     *   }
     * </pre>
     */
    void style(String name, @DelegatesTo(StyleDsl) Closure cl) {
        def dsl = new StyleDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _styles[name] = dsl.build(name)
    }

    // -------------------------------------------------------------------------
    // Sheets
    // -------------------------------------------------------------------------

    /**
     * Add a sheet to the workbook.
     * <pre>
     *   sheet('Summary') {
     *       row 1, style:'header', values:['A','B','C']
     *       rows dataKey:'myData', startAt:2
     *   }
     * </pre>
     */
    void sheet(String name, @DelegatesTo(SheetDsl) Closure cl) {
        def dsl = new SheetDsl(name)
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _sheetDsls << dsl
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    /**
     * Materialise the DSL state into an immutable {@link WorkbookPlan}.
     *
     * @return a fully-populated WorkbookPlan ready for the injected xlsxCallFn.
     */
    WorkbookPlan build() {
        Map<String, Object> options = [
            createIfMissing       : _createIfMissing,
            preserveExistingStyles: _preserveExistingStyles,
            evaluateFormulas      : _evaluateFormulas
        ]

        new WorkbookPlan(
            styles      : new LinkedHashMap<>(_styles),
            sheets      : _sheetDsls.collect { it.build() },
            options     : options,
            templatePath: _templatePath,
            namedRanges : new LinkedHashMap<>(_namedRanges)   // field added to WorkbookPlan in Turn 2
        )
    }
}
