package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.dag.task.docx.DocumentDsl
import org.softwood.dag.task.docx.DocumentPlan

/**
 * A DAG task that reads from or generates Word documents (.docx).
 *
 * <p><b>Library contract:</b> this class is POI-agnostic. It depends only on
 * GroovyConcurrentUtils. The server factory injects the actual POI implementation
 * via {@link #docxCallFn}.</p>
 *
 * <h3>Actions:</h3>
 * <pre>
 *   READ             – Read document content, returns structured Map.
 *   GENERATE         – Create a new document from templatePath or scratch.
 *   APPEND_SECTION   – Append content after the last paragraph / end of doc.
 *   REPLACE_SECTION  – Replace the content under a named heading.
 *   EXTRACT_TEXT     – Return full plain-text content as a String.
 *   SCRIPT           – Use a {@link DocumentDsl} closure to describe the document.
 * </pre>
 *
 * <h3>DSL usage:</h3>
 * <pre>
 *   docxTask('generate-report') {
 *       file      '/reports/q3.docx'
 *       outputKey 'reportResult'
 *       document {
 *           title 'Q3 Report'
 *           section { heading 'Summary'; text 'Revenue grew 12%...' }
 *           section { heading 'Risks', 2; bullets ['Volatility', 'Supply chain'] }
 *       }
 *   }
 * </pre>
 *
 * <h3>Legacy flat-map usage:</h3>
 * <pre>
 *   docxTask('generate-doc') {
 *       file      '/reports/q3.docx'
 *       content   ['Summary': 'text...', 'Risks': ['r1', 'r2']]
 *       outputKey 'docResult'
 *   }
 * </pre>
 *
 * <h3>Injection:</h3>
 * <pre>
 *   task.docxCallFn = { String action, Map config -> Object result }
 * </pre>
 *
 * <h3>Effect class:</h3>
 * {@code PURE} for READ / EXTRACT_TEXT; {@code IDEMPOTENT} for all write actions.
 */
@Slf4j
class DocxTask extends TaskBase<Object> {

    enum DocxAction { READ, GENERATE, APPEND_SECTION, REPLACE_SECTION, EXTRACT_TEXT, SCRIPT }

    // -------------------------------------------------------------------------
    // Resolver-aware config fields
    // -------------------------------------------------------------------------

    /** Absolute path to the .docx file (resolver-aware). */
    Object filePathValue

    /** WorkingMemory key under which to store the task result (resolver-aware). */
    Object outputKeyValue

    /** WorkingMemory key to pull the content map from (resolver-aware). */
    Object contentKeyValue

    // -------------------------------------------------------------------------
    // Action
    // -------------------------------------------------------------------------

    /** Current action. Default {@code GENERATE}. */
    DocxAction action = DocxAction.GENERATE

    // -------------------------------------------------------------------------
    // Legacy flat-map fields (backward-compatible)
    // -------------------------------------------------------------------------

    /** Optional merge-field template .docx for GENERATE action. */
    String templatePath

    /**
     * Content map for GENERATE / APPEND_SECTION:
     * sectionHeading → String (paragraph) or List&lt;String&gt; (bullet list).
     */
    Map<String, Object> content

    /**
     * Target section heading for REPLACE_SECTION action.
     * Must exactly match the heading text in the document.
     */
    String targetSection

    // -------------------------------------------------------------------------
    // DSL hook
    // -------------------------------------------------------------------------

    /**
     * Document DSL closure — set by {@link #document(Closure)}.
     * When non-null, action is set to {@code SCRIPT} automatically.
     */
    Closure documentDslClosure

    // -------------------------------------------------------------------------
    // Injected adapter
    // -------------------------------------------------------------------------

    /**
     * The POI implementation injected by the server factory.
     * Signature: {@code { String action, Map config -> Object result }}
     * Must be non-null at execution time.
     */
    Closure<Object> docxCallFn

    // -------------------------------------------------------------------------
    // Fluent setters
    // -------------------------------------------------------------------------

    void file(Object v)       { filePathValue  = v }
    void outputKey(Object v)  { outputKeyValue = v }
    void contentKey(Object v) { contentKeyValue = v }

    void action(String a)     { action = DocxAction.valueOf(a.toUpperCase()) }

    /**
     * Attach a DSL document closure. Sets {@code action = SCRIPT}.
     * <pre>
     *   document {
     *       title 'Q3 Report'
     *       section { heading 'Summary'; text '...' }
     *   }
     * </pre>
     */
    void document(@DelegatesTo(DocumentDsl) Closure cl) {
        documentDslClosure = cl
        action             = DocxAction.SCRIPT
    }

    // -------------------------------------------------------------------------
    // Effect class — computed from action
    // -------------------------------------------------------------------------

    EffectClass getEffectClass() {
        action in [DocxAction.READ, DocxAction.EXTRACT_TEXT] ? EffectClass.PURE : EffectClass.IDEMPOTENT
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    DocxTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // -------------------------------------------------------------------------
    // Core execution
    // -------------------------------------------------------------------------

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        if (!docxCallFn) {
            throw new IllegalStateException("DocxTask '${id}': docxCallFn has not been injected by the server factory")
        }

        String filePath  = evaluateValue(filePathValue,  ctx, prevValue) as String
        String outputKey = evaluateValue(outputKeyValue, ctx, prevValue) as String

        if (!filePath) {
            throw new IllegalStateException("DocxTask '${id}': filePath is required")
        }

        Map<String, Object> memory = resolveWorkingMemory(ctx)

        return ctx.promiseFactory.executeAsync {
            Map<String, Object> config

            if (documentDslClosure) {
                // DSL path — build a DocumentPlan from the closure
                def dsl = new DocumentDsl(ctx, prevValue)
                executeDeferredConfig(documentDslClosure, dsl, ctx, prevValue)
                DocumentPlan plan = dsl.build()
                config = [filePath: filePath, action: 'SCRIPT', documentPlan: plan]
            } else {
                // Legacy flat-map path
                config = buildConfig(memory, prevValue)
                config.filePath = filePath
            }

            log.debug "DocxTask '${id}': executing action=${action} on ${filePath}"
            Object result = docxCallFn.call(action.name(), config)

            if (outputKey && result != null) {
                storeResult(ctx, outputKey, result)
            }

            log.info "DocxTask '${id}': completed, result type=${result?.getClass()?.simpleName}"
            result
        }
    }

    // -------------------------------------------------------------------------
    // Config builder (legacy path)
    // -------------------------------------------------------------------------

    private Map<String, Object> buildConfig(Map<String, Object> memory, Object prevValue) {
        Map<String, Object> config = [
            action: action.name()
        ] as Map<String, Object>

        if (templatePath)  config.templatePath  = templatePath
        if (targetSection) config.targetSection = targetSection

        // Resolve content: explicit field > contentKey from memory > prevValue (if Map)
        String ck = evaluateValue(contentKeyValue, null, prevValue) as String
        if (content) {
            config.content = content
        } else if (ck) {
            def memContent = memory?.sharedFacts?.get(ck) ?: memory?.runContext?.get(ck)
            config.content = (memContent instanceof Map) ? memContent
                           : (prevValue instanceof Map)  ? prevValue
                           : null
        } else if (prevValue instanceof Map) {
            config.content = prevValue
        }

        return config
    }

    // -------------------------------------------------------------------------
    // Stub — full implementation wired in Turn 4
    // -------------------------------------------------------------------------

    /**
     * Execute a structured query against a Word document.
     * Full implementation injected in Turn 4 via the adapter.
     *
     * @param plan the query / extraction descriptor
     * @return structured result (format defined by the adapter)
     */
    DocumentPlan query(DocumentPlan plan) {
        throw new UnsupportedOperationException('DocxTask does not support query operations')
    }

    // -------------------------------------------------------------------------
    // WorkingMemory helpers
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
                log.debug "DocxTask '${id}': stored result under sharedFacts['${key}']"
            }
        }
    }
}
