package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.dag.task.pptx.PresentationDsl
import org.softwood.dag.task.pptx.PresentationPlan

/**
 * A DAG task that reads from or generates PowerPoint presentations (.pptx).
 *
 * <p><b>Library contract:</b> this class is POI-agnostic. It depends only on
 * GroovyConcurrentUtils. The server factory injects the actual POI implementation
 * via {@link #pptxCallFn}.</p>
 *
 * <h3>Actions:</h3>
 * <pre>
 *   READ         – Read slide content; returns List&lt;Map&gt; of slide descriptors.
 *   GENERATE     – Create a new presentation from templatePath or scratch.
 *   ADD_SLIDE    – Append one or more slides to an existing presentation.
 *   EXTRACT_TEXT – Return all text from all slides as a single String.
 *   MERGE        – Merge slides from multiple source files into one output.
 *   CONVERT      – Convert slides to another format (e.g. PDF, PNG).
 *   SCRIPT       – Use a {@link PresentationDsl} closure to describe the deck.
 * </pre>
 *
 * <h3>DSL usage:</h3>
 * <pre>
 *   pptxTask('generate-deck') {
 *       file      '/decks/q3-review.pptx'
 *       outputKey 'deckResult'
 *       presentation {
 *           title 'Q3 Financial Review'
 *           slide { title 'Overview'; shape { text 'Revenue grew 12%' } }
 *           slide { title 'Data';     shape { table { headers ['Q','Rev']; row ['Q1','1.2M'] } } }
 *       }
 *   }
 * </pre>
 *
 * <h3>Legacy flat-map usage:</h3>
 * <pre>
 *   pptxTask('render-deck') {
 *       file      '/decks/sprint-report.pptx'
 *       slidesKey 'deckPlan'              // List&lt;Map&gt; from prior LlmTask
 *       outputKey 'deckResult'
 *   }
 * </pre>
 *
 * <h3>Injection:</h3>
 * <pre>
 *   task.pptxCallFn = { String action, Map config -> Object result }
 * </pre>
 *
 * <h3>Effect class:</h3>
 * {@code PURE} for READ / EXTRACT_TEXT; {@code IDEMPOTENT} for all write actions.
 */
@Slf4j
class PptxTask extends TaskBase<Object> {

    enum PptxAction { READ, GENERATE, ADD_SLIDE, EXTRACT_TEXT, MERGE, CONVERT, SCRIPT }

    // -------------------------------------------------------------------------
    // Resolver-aware config fields
    // -------------------------------------------------------------------------

    /** Absolute path to the .pptx file (resolver-aware). */
    Object filePathValue

    /** WorkingMemory key under which to store the task result (resolver-aware). */
    Object outputKeyValue

    /** WorkingMemory key to pull slide data from (resolver-aware). */
    Object slidesKeyValue

    // -------------------------------------------------------------------------
    // Action
    // -------------------------------------------------------------------------

    /** Current action. Default {@code GENERATE}. */
    PptxAction action = PptxAction.GENERATE

    // -------------------------------------------------------------------------
    // Legacy flat-map fields (backward-compatible)
    // -------------------------------------------------------------------------

    /** Optional master/theme template .pptx for GENERATE action. */
    String templatePath

    /**
     * Slide descriptors: {@code List<Map>} each with keys:
     * {@code title}, {@code content}, {@code layout}, {@code notes}.
     * Takes precedence over {@link #slidesKeyValue} if both are set.
     */
    List<Map<String, Object>> slides

    // -------------------------------------------------------------------------
    // DSL hook
    // -------------------------------------------------------------------------

    /**
     * Presentation DSL closure — set by {@link #presentation(Closure)}.
     * When non-null, action is set to {@code SCRIPT} automatically.
     */
    Closure presentationDslClosure

    // -------------------------------------------------------------------------
    // Injected adapter
    // -------------------------------------------------------------------------

    /**
     * The POI implementation injected by the server factory.
     * Signature: {@code { String action, Map config -> Object result }}
     * Must be non-null at execution time.
     */
    Closure<Object> pptxCallFn

    // -------------------------------------------------------------------------
    // Fluent setters
    // -------------------------------------------------------------------------

    void file(Object v)       { filePathValue  = v }
    void outputKey(Object v)  { outputKeyValue = v }
    void slidesKey(Object v)  { slidesKeyValue = v }

    void action(String a)     { action = PptxAction.valueOf(a.toUpperCase()) }

    /**
     * Attach a DSL presentation closure. Sets {@code action = SCRIPT}.
     * <pre>
     *   presentation {
     *       title 'Q3 Review'
     *       slide { title 'Overview'; shape { text 'Revenue grew 12%' } }
     *   }
     * </pre>
     */
    void presentation(@DelegatesTo(PresentationDsl) Closure cl) {
        presentationDslClosure = cl
        action                 = PptxAction.SCRIPT
    }

    // -------------------------------------------------------------------------
    // Effect class — computed from action
    // -------------------------------------------------------------------------

    EffectClass getEffectClass() {
        action in [PptxAction.READ, PptxAction.EXTRACT_TEXT] ? EffectClass.PURE : EffectClass.IDEMPOTENT
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    PptxTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // -------------------------------------------------------------------------
    // Core execution
    // -------------------------------------------------------------------------

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        if (!pptxCallFn) {
            throw new IllegalStateException("PptxTask '${id}': pptxCallFn has not been injected by the server factory")
        }

        String filePath  = evaluateValue(filePathValue,  ctx, prevValue) as String
        String outputKey = evaluateValue(outputKeyValue, ctx, prevValue) as String

        if (!filePath) {
            throw new IllegalStateException("PptxTask '${id}': filePath is required")
        }

        Map<String, Object> memory = resolveWorkingMemory(ctx)

        return ctx.promiseFactory.executeAsync {
            Map<String, Object> config

            if (presentationDslClosure) {
                // DSL path — build a PresentationPlan from the closure
                def dsl = new PresentationDsl(ctx, prevValue)
                executeDeferredConfig(presentationDslClosure, dsl, ctx, prevValue)
                PresentationPlan plan = dsl.build()
                config = [filePath: filePath, action: 'SCRIPT', presentationPlan: plan]
            } else {
                // Legacy flat-map path
                config = buildConfig(memory, prevValue)
                config.filePath = filePath
            }

            log.debug "PptxTask '${id}': executing action=${action} on ${filePath}"
            Object result = pptxCallFn.call(action.name(), config)

            if (outputKey && result != null) {
                storeResult(ctx, outputKey, result)
            }

            log.info "PptxTask '${id}': completed, result type=${result?.getClass()?.simpleName}"
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

        if (templatePath) config.templatePath = templatePath

        // Resolve slides: explicit field > slidesKey from memory > prevValue (if List)
        String sk = evaluateValue(slidesKeyValue, null, prevValue) as String
        if (slides) {
            config.slides = slides
        } else if (sk) {
            def memSlides = memory?.sharedFacts?.get(sk) ?: memory?.runContext?.get(sk)
            config.slides = (memSlides instanceof List) ? memSlides
                          : (prevValue instanceof List)  ? prevValue
                          : null
        } else if (prevValue instanceof List) {
            config.slides = prevValue
        }

        return config
    }

    // -------------------------------------------------------------------------
    // Stub — full implementation wired in Turn 5
    // -------------------------------------------------------------------------

    /**
     * Execute a structured query against a PowerPoint presentation.
     * Full implementation injected in Turn 5 via the adapter.
     *
     * @param plan the query / extraction descriptor
     * @return structured result (format defined by the adapter)
     */
    PresentationPlan query(PresentationPlan plan) {
        throw new UnsupportedOperationException('PptxTask does not support query operations')
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
                log.debug "PptxTask '${id}': stored result under sharedFacts['${key}']"
            }
        }
    }
}
