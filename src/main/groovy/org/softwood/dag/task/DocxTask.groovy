package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

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
 * </pre>
 *
 * <h3>Params:</h3>
 * <pre>
 *   filePath      – Required. Absolute path to the .docx file (output or input).
 *   templatePath  – Optional. Merge-field template .docx for GENERATE action.
 *   outputKey     – WorkingMemory key to store result under (optional).
 *   content       – Map&lt;String,Object&gt; of sectionName -&gt; content for GENERATE/APPEND.
 *   contentKey    – WorkingMemory key to pull content map from (alternative to content).
 *   targetSection – Heading text to target for REPLACE_SECTION.
 * </pre>
 *
 * <h3>Content map structure for GENERATE:</h3>
 * <pre>
 *   [
 *     'Executive Summary': 'Summary text...',
 *     'Key Risks': ['Risk 1', 'Risk 2'],   // List becomes bullet list
 *     'Appendix': 'Appendix text...'
 *   ]
 * </pre>
 *
 * <h3>Injection:</h3>
 * <pre>
 *   task.docxCallFn = { String action, Map config -&gt; Object result }
 * </pre>
 *
 * <h3>Effect class:</h3>
 * IDEMPOTENT (GENERATE/REPLACE overwrite same output file); PURE for READ/EXTRACT_TEXT.
 */
@Slf4j
class DocxTask extends TaskBase<Object> {

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    /** Action: READ | GENERATE | APPEND_SECTION | REPLACE_SECTION | EXTRACT_TEXT */
    String action = 'GENERATE'

    /** Absolute path to the .docx file. Required. */
    String filePath

    /** Optional merge-field template .docx for GENERATE action. */
    String templatePath

    /** WorkingMemory key under which to store the task result. */
    String outputKey

    /**
     * Content map for GENERATE/APPEND_SECTION:
     * sectionHeading -&gt; String (paragraph) or List&lt;String&gt; (bullet list).
     * Takes precedence over contentKey if both are set.
     */
    Map<String, Object> content

    /**
     * WorkingMemory key to pull the content map from.
     * Expects a Map&lt;String,Object&gt; in the same structure as content.
     * Used when LLM output (from a prior LlmTask) drives doc generation.
     */
    String contentKey

    /**
     * Target section heading for REPLACE_SECTION action.
     * Must exactly match the heading text in the document.
     */
    String targetSection

    /**
     * The POI implementation injected by the server factory.
     * Signature: { String action, Map config -&gt; Object result }
     * Must be non-null at execution time.
     */
    Closure<Object> docxCallFn

    /** Effect class: IDEMPOTENT (writes overwrite same output). */
    final EffectClass effectClass = EffectClass.IDEMPOTENT

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
        if (!filePath) {
            throw new IllegalStateException("DocxTask '${id}': filePath is required")
        }

        Map<String, Object> memory = resolveWorkingMemory(ctx)

        return ctx.promiseFactory.executeAsync {
            Map config = buildConfig(memory, prevValue)
            log.debug "DocxTask '${id}': executing action=${action} on ${filePath}"

            Object result = docxCallFn.call(action.toUpperCase(), config)

            if (outputKey && result != null) {
                storeResult(ctx, outputKey, result)
            }

            log.info "DocxTask '${id}': completed, result type=${result?.getClass()?.simpleName}"
            result
        }
    }

    // -------------------------------------------------------------------------
    // Config builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildConfig(Map<String, Object> memory, Object prevValue) {
        Map<String, Object> config = [
            filePath : filePath,
            action   : action.toUpperCase()
        ] as Map<String, Object>

        if (templatePath)   config.templatePath  = templatePath
        if (targetSection)  config.targetSection = targetSection

        // Resolve content: explicit content > contentKey from memory > prevValue (if Map)
        if (content) {
            config.content = content
        } else if (contentKey) {
            def memContent = memory?.sharedFacts?.get(contentKey) ?:
                             memory?.runContext?.get(contentKey)
            if (memContent instanceof Map) {
                config.content = memContent
            } else if (prevValue instanceof Map) {
                config.content = prevValue
            }
        } else if (prevValue instanceof Map) {
            config.content = prevValue
        }

        return config
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
