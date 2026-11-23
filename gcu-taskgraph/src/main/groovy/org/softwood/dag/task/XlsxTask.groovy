package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.dag.task.xlsx.CellSpec
import org.softwood.dag.task.xlsx.DataRowsSpec
import org.softwood.dag.task.xlsx.QueryPlan
import org.softwood.dag.task.xlsx.RowSpec
import org.softwood.dag.task.xlsx.SheetPlan
import org.softwood.dag.task.xlsx.WorkbookPlan
import org.softwood.dag.task.xlsx.XlsxWorkbookDsl

/**
 * A DAG task that reads from or writes to Excel workbooks (.xlsx).
 *
 * <p><b>Library contract:</b> this class is POI-agnostic. It depends only on
 * GroovyConcurrentUtils. The server factory injects the actual POI implementation
 * via {@link #xlsxCallFn}.</p>
 *
 * <h3>Actions:</h3>
 * <pre>
 *   READ      – Read rows from a sheet, returns List&lt;Map&gt; by default.
 *   WRITE     – Write headers + rows to a sheet (replaces that sheet; other sheets kept).
 *   APPEND    – Append rows after the sheet's last row (headers first when the file is new).
 *   TRANSFORM – Read the sheet, apply rowTransformer to each row, write it back.
 *   CREATE    – Create a new workbook from headers + rows.
 *   GENERATE  – Build the workbook from a {@code workbook { }} closure (also APPEND/TRANSFORM with one).
 * </pre>
 * <p>GCU 2.0.1: before it only READ worked -- WRITE and CREATE were unknown to the adapter, APPEND and TRANSFORM sent
 * rows where it needs a plan, and {@code workbook { }} was never read. {@code useLlmTransform} is refused by name:
 * no LLM transform is implemented.</p>
 *
 * <h3>Params:</h3>
 * <pre>
 *   filePath      – Required. Absolute path to the .xlsx file.
 *   sheetName     – Sheet to read/write (default: 'Sheet1').
 *   outputKey     – WorkingMemory key to store result under (optional).
 *   cellRange     – Optional A1:F20 range for READ (null = whole sheet).
 *   readFormat    – Output format for READ: list_of_maps | list_of_lists | map_by_key (default: list_of_maps).
 *   keyColumn     – Column name used as key for map_by_key readFormat.
 *   headers       – Column headers for WRITE/CREATE.
 *   rows          – Row data: List&lt;List&lt;Object&gt;&gt; for WRITE/APPEND.
 *   dataKey       – WorkingMemory key to pull row data from (alternative to rows).
 *   rowTransformer – Optional Closure&lt;Map&gt;: { Map row -&gt; Map transformed } for TRANSFORM.
 *   useLlmTransform – Delegate row transform to injected llmCallFn (default: false).
 * </pre>
 *
 * <h3>Injection:</h3>
 * <pre>
 *   task.xlsxCallFn = { String action, Map config -&gt; Object result }
 * </pre>
 *
 * <h3>Effect class:</h3>
 * IDEMPOTENT for WRITE/CREATE (overwrites same output); PURE for READ.
 */
@Slf4j
class XlsxTask extends TaskBase<Object> {

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    /** Action to perform: READ | WRITE | APPEND | TRANSFORM | CREATE */
    String action = 'READ'

    /** Absolute path to the .xlsx file. Required. */
    String filePath

    /** Sheet name to target (default: Sheet1). */
    String sheetName = 'Sheet1'

    /** WorkingMemory key under which to store the task result. */
    String outputKey

    /** Optional A1-notation range for READ (null = whole used range). */
    String cellRange

    /**
     * Output format for READ action.
     * list_of_maps  – List&lt;Map&lt;String,Object&gt;&gt; keyed by header row (default)
     * list_of_lists – List&lt;List&lt;Object&gt;&gt; raw rows
     * map_by_key    – Map&lt;Object,Map&gt; keyed by the column named in keyColumn
     */
    String readFormat = 'list_of_maps'

    /** Column name to use as key when readFormat='map_by_key'. */
    String keyColumn

    /** Column headers for WRITE/CREATE actions. */
    List<String> headers

    /**
     * Row data for WRITE/APPEND: List&lt;List&lt;Object&gt;&gt;.
     * Takes precedence over dataKey if both are set.
     */
    List<List<Object>> rows

    /**
     * WorkingMemory key to pull row data from (alternative to setting rows directly).
     * Expects the stored value to be a List&lt;List&lt;Object&gt;&gt; or List&lt;Map&gt;.
     */
    String dataKey

    /**
     * Optional row-level transform closure for the TRANSFORM action.
     * Signature: { Map row -&gt; Map transformedRow }
     * Mutually exclusive with useLlmTransform.
     */
    Closure<Map> rowTransformer

    /**
     * When true, the TRANSFORM action delegates row transformation to the
     * injected llmCallFn rather than rowTransformer closure.
     * Requires llmCallFn to be injected separately.
     */
    boolean useLlmTransform = false

    /**
     * Optional LLM call function for LLM-assisted transforms.
     * Signature: { String systemPrompt, String userPrompt, String tier, float temp -&gt; String raw }
     * Only used when useLlmTransform=true.
     */
    Closure<String> llmCallFn

    /**
     * The POI implementation injected by the server factory.
     * Signature: { String action, Map config -&gt; Object result }
     * Must be non-null at execution time.
     */
    Closure<Object> xlsxCallFn

    /** Effect class: IDEMPOTENT (writes overwrite same output). */
    final EffectClass effectClass = EffectClass.IDEMPOTENT

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    XlsxTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // -------------------------------------------------------------------------
    // Core execution
    // -------------------------------------------------------------------------

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        if (!xlsxCallFn) {
            throw new IllegalStateException("XlsxTask '${id}': xlsxCallFn has not been injected by the server factory")
        }
        if (!filePath) {
            throw new IllegalStateException("XlsxTask '${id}': filePath is required")
        }

        Map<String, Object> memory = resolveWorkingMemory(ctx)

        return ctx.promiseFactory.executeAsync {
            String act = (action ?: 'READ').toUpperCase()
            if (!ACTIONS.contains(act)) {
                throw new IllegalArgumentException("XlsxTask '${id}': unknown action '${action}' (${ACTIONS.join(', ')})".toString())
            }
            log.debug "XlsxTask '${id}': executing action=${act} on ${filePath}"

            Object result
            if (workbookDslClosure) {
                // 2.0.1: the workbook { } DSL builds a WorkbookPlan; its deferredRows take their data from working
                // memory (sharedFacts, then runContext), or from the previous result when that is a map.
                if (!PLAN_ACTIONS.contains(act)) {
                    throw new IllegalArgumentException("XlsxTask '${id}': workbook { } needs action GENERATE, APPEND or TRANSFORM, not ${act}".toString())
                }
                def dsl = new XlsxWorkbookDsl(ctx, prevValue, this)
                executeDeferredConfig(workbookDslClosure, dsl, ctx, prevValue)
                WorkbookPlan plan = dsl.build()
                result = xlsxCallFn.call(act, [filePath: filePath, plan: plan, data: deferredData(plan, memory, prevValue)])
            } else {
                result = runFlat(act, buildConfig(memory, prevValue))
            }

            if (outputKey && result != null) {
                storeResult(ctx, outputKey, result)
            }

            log.info "XlsxTask '${id}': completed, result type=${result?.getClass()?.simpleName}"
            result
        }
    }

    /** Actions this task takes. READ/QUERY/GENERATE/APPEND/TRANSFORM go to the adapter; WRITE and CREATE are row writes. */
    static final List<String> ACTIONS = ['READ', 'WRITE', 'CREATE', 'APPEND', 'TRANSFORM', 'GENERATE']
    private static final Set<String> PLAN_ACTIONS = ['GENERATE', 'APPEND', 'TRANSFORM'] as Set
    private static final String ROWS_KEY = '__xlsxTaskRows'

    /** The workbook { } closure, when the DSL is used (2.0.1: it was never read before). */
    Closure workbookDslClosure

    /**
     * Build the workbook from a {@link XlsxWorkbookDsl} closure. Sets the action to GENERATE unless it is already
     * APPEND or TRANSFORM.
     */
    void workbook(@DelegatesTo(XlsxWorkbookDsl) Closure cl) {
        workbookDslClosure = cl
        if (!['APPEND', 'TRANSFORM'].contains((action ?: '').toUpperCase())) action = 'GENERATE'
    }

    /**
     * The flat (no-DSL) actions, expressed as plans the adapter takes. Before 2.0.1 only READ worked: WRITE and CREATE
     * reached the adapter as unknown actions, and APPEND and TRANSFORM sent rows where the adapter needs a plan.
     */
    private Object runFlat(String act, Map<String, Object> config) {
        List<List<Object>> data = normaliseRows(config.rows as List)
        switch (act) {
            case 'READ':
                return xlsxCallFn.call('READ', config)
            case 'CREATE':
                return xlsxCallFn.call('GENERATE', [filePath: filePath, plan: rowsPlan(true, headers ? 2 : 1), data: [(ROWS_KEY): data]])
            case 'WRITE':
                // replaces this sheet; other sheets in an existing workbook are kept
                return xlsxCallFn.call(new File(filePath).exists() ? 'TRANSFORM' : 'GENERATE',
                                       [filePath: filePath, plan: rowsPlan(true, headers ? 2 : 1), data: [(ROWS_KEY): data]])
            case 'APPEND':
                // rows after the sheet's last row; a new file gets the headers first
                boolean fresh = !new File(filePath).exists()
                WorkbookPlan p = fresh ? rowsPlan(true, headers ? 2 : 1) : rowsPlan(false, null)
                return xlsxCallFn.call('APPEND', [filePath: filePath, plan: p, data: [(ROWS_KEY): data]])
            case 'TRANSFORM':
                return transformRows()
            default: // GENERATE with no workbook { }
                throw new IllegalArgumentException("XlsxTask '${id}': GENERATE needs a workbook { } closure".toString())
        }
    }

    private Object transformRows() {
        if (useLlmTransform) {
            throw new UnsupportedOperationException("XlsxTask '${id}': useLlmTransform is not implemented -- pass a rowTransformer closure".toString())
        }
        if (!rowTransformer) {
            throw new IllegalArgumentException("XlsxTask '${id}': TRANSFORM needs a rowTransformer closure".toString())
        }
        Map read = xlsxCallFn.call('READ', [filePath: filePath, sheetName: sheetName, readFormat: 'list_of_maps']) as Map
        List<Map> before = (read?.get(sheetName) ?: []) as List<Map>
        List<Map> after  = before.collect { Map row -> rowTransformer.call(row) as Map }
        List<String> hdrs = after ? (after[0].keySet().collect { it as String }) : (headers ?: [])
        List<List<Object>> data = after.collect { Map row -> hdrs.collect { String h -> row[h] } }
        WorkbookPlan plan = new WorkbookPlan(sheets: [new SheetPlan(name: sheetName,
            rows: hdrs ? [new RowSpec(rowIndex: 1, cells: hdrs.collect { new CellSpec(value: it) })] : [],
            deferredRows: [new DataRowsSpec(startAt: hdrs ? 2 : 1, dataKey: ROWS_KEY)])])
        xlsxCallFn.call('TRANSFORM', [filePath: filePath, plan: plan, data: [(ROWS_KEY): data]])
        return [filePath: filePath, sheet: sheetName, transformed: data.size()]
    }

    /** One sheet: an optional header row (row 1), then the task's rows from {@link #ROWS_KEY} from startAt (1-based; null = after the last row). */
    private WorkbookPlan rowsPlan(boolean withHeaders, Integer startAt) {
        List<RowSpec> head = (withHeaders && headers) ? [new RowSpec(rowIndex: 1, cells: headers.collect { new CellSpec(value: it) })] : []
        new WorkbookPlan(sheets: [new SheetPlan(name: sheetName, rows: head,
            deferredRows: [new DataRowsSpec(startAt: startAt, dataKey: ROWS_KEY)])])
    }

    /** Rows as lists: a list of maps is laid out in {@link #headers} order when given, else the first map's key order. */
    private List<List<Object>> normaliseRows(List rows) {
        if (!rows) return []
        if (rows[0] instanceof Map) {
            List<String> keys = headers ?: ((rows[0] as Map).keySet().collect { it as String })
            if (!headers) headers = keys
            return rows.collect { Object r -> keys.collect { String k -> (r as Map)[k] } }
        }
        return rows.collect { Object r -> r instanceof List ? (r as List) : [r] }
    }

    /** Data for the plan's deferredRows keys: working memory first, then a map previous result. Missing keys are left out (the adapter refuses them by name). */
    private static Map deferredData(WorkbookPlan plan, Map<String, Object> memory, Object prevValue) {
        Map data = [:]
        plan.sheets.each { SheetPlan sp ->
            sp.deferredRows?.each { DataRowsSpec d ->
                String k = d.dataKey
                if (!k || data.containsKey(k)) return
                Object v = (memory?.sharedFacts as Map)?.get(k)
                if (v == null) v = (memory?.runContext as Map)?.get(k)
                if (v == null && prevValue instanceof Map && (prevValue as Map).containsKey(k)) v = (prevValue as Map)[k]
                if (v != null) data[k] = v
            }
        }
        return data
    }

    // -------------------------------------------------------------------------
    // Config builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildConfig(Map<String, Object> memory, Object prevValue) {
        Map<String, Object> config = [
            filePath  : filePath,
            sheetName : sheetName,
            action    : action.toUpperCase()
        ] as Map<String, Object>

        if (cellRange)   config.cellRange  = cellRange
        if (readFormat)  config.readFormat = readFormat
        if (keyColumn)   config.keyColumn  = keyColumn
        if (headers)     config.headers    = headers

        // Resolve row data: explicit rows > dataKey from memory > prevValue
        if (rows) {
            config.rows = rows
        } else if (dataKey) {
            def memRows = memory?.resultsByNode?.values()?.find { it != null } ?:
                          memory?.sharedFacts?.get(dataKey) ?:
                          memory?.runContext?.get(dataKey)
            // Prefer direct key lookup in sharedFacts / runContext
            def directRows = memory?.sharedFacts?.get(dataKey) ?: memory?.runContext?.get(dataKey)
            if (directRows != null) config.rows = directRows
            else if (prevValue instanceof List) config.rows = prevValue
        } else if (prevValue instanceof List) {
            config.rows = prevValue
        }

        if (rowTransformer && !useLlmTransform) config.rowTransformer = rowTransformer
        if (useLlmTransform && llmCallFn)       config.llmCallFn = llmCallFn

        return config
    }

    // -------------------------------------------------------------------------
    // WorkingMemory helpers (mirrors LlmTask pattern)
    // -------------------------------------------------------------------------

    private Map<String, Object> resolveWorkingMemory(TaskContext taskCtx) {
        def wm = taskCtx?.globals?.get('__workingMemory')
        return wm instanceof Map ? (Map<String, Object>) wm : [:]
    }

    private void storeResult(TaskContext taskCtx, String key, Object value) {
        def wm = taskCtx?.globals?.get('__workingMemory')
        if (wm instanceof Map) {
            def sharedFacts = wm['sharedFacts']
            if (sharedFacts instanceof Map) {
                sharedFacts[key] = value
                log.debug "XlsxTask '${id}': stored result under sharedFacts['${key}']"
            }
        }
    }

    /**
     * Execute a structured query against an Excel file, returning a filtered,
     * optionally coerced list of rows.
     *
     * <p>Full implementation is wired up in Turn 4 via the injected
     * {@code xlsxCallFn} adapter.  This stub allows DSL-built tasks to compile
     * and be wired before the adapter is ready.</p>
     *
     * @param plan the query descriptor built by {@link org.softwood.dag.task.xlsx.QueryDsl}
     * @return rows in the format specified by {@link org.softwood.dag.task.xlsx.QueryPlan#format}: a List, or for
     *         map_by_key a Map keyed by the key column (2.0.2: declared Object; as List it turned that Map into a
     *         list of entries)
     */
    Object query(QueryPlan plan) {
        if (!xlsxCallFn) throw new IllegalStateException("XlsxTask '${id}': xlsxCallFn has not been injected by the server factory")
        if (!filePath)   throw new IllegalStateException("XlsxTask '${id}': filePath is required for query()")
        return xlsxCallFn.call('QUERY', [query: plan, filePath: filePath])
    }
}