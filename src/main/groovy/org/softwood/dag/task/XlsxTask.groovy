package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

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
 *   WRITE     – Write headers + rows to a sheet (overwrites).
 *   APPEND    – Append rows to an existing sheet.
 *   TRANSFORM – Read, apply rowTransformer (or LLM), write back.
 *   CREATE    – Create a new workbook from scratch.
 * </pre>
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
            Map config = buildConfig(memory, prevValue)
            log.debug "XlsxTask '${id}': executing action=${action} on ${filePath}"

            Object result = xlsxCallFn.call(action.toUpperCase(), config)

            if (outputKey && result != null) {
                storeResult(ctx, outputKey, result)
            }

            log.info "XlsxTask '${id}': completed, result type=${result?.getClass()?.simpleName}"
            result
        }
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
}
