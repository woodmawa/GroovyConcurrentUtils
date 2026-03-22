package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

/**
 * A DAG task that reads from or generates PowerPoint presentations (.pptx).
 *
 * <p><b>Library contract:</b> this class is POI-agnostic. It depends only on
 * GroovyConcurrentUtils. The server factory injects the actual POI implementation
 * via {@link #pptxCallFn}.</p>
 *
 * <h3>Actions:</h3>
 * <pre>
 *   READ         – Read slide content, returns List&lt;Map&gt; of slide descriptors.
 *   GENERATE     – Create a new presentation from templatePath or scratch.
 *   ADD_SLIDE    – Append one or more slides to an existing presentation.
 *   EXTRACT_TEXT – Return all text from all slides as a single String.
 * </pre>
 *
 * <h3>Params:</h3>
 * <pre>
 *   filePath     – Required. Absolute path to the .pptx file.
 *   templatePath – Optional. Master/theme template .pptx for GENERATE.
 *   outputKey    – WorkingMemory key to store result under (optional).
 *   slides       – List of slide descriptor maps (see structure below).
 *   slidesKey    – WorkingMemory key to pull slides from (alternative to slides).
 * </pre>
 *
 * <h3>Slide descriptor structure:</h3>
 * <pre>
 *   [
 *     title  : 'Slide Title',
 *     content: 'Body text, or a List&lt;String&gt; for bullet points',
 *     layout : 'TITLE_CONTENT' | 'TITLE_ONLY' | 'BLANK' | 'TWO_CONTENT',
 *     notes  : 'Optional speaker notes'
 *   ]
 * </pre>
 *
 * <h3>LLM chaining pattern:</h3>
 * <pre>
 *   // Prior LlmTask stores structured slide plan to 'deckPlan' in WorkingMemory
 *   // PptxTask picks it up via slidesKey:
 *   pptx("render-deck") {
 *       action = 'GENERATE'
 *       slidesKey = 'deckPlan'
 *       filePath = "output/sprint-report.pptx"
 *   }
 * </pre>
 *
 * <h3>Injection:</h3>
 * <pre>
 *   task.pptxCallFn = { String action, Map config -&gt; Object result }
 * </pre>
 *
 * <h3>Effect class:</h3>
 * IDEMPOTENT (GENERATE overwrites same output); PURE for READ/EXTRACT_TEXT.
 */
@Slf4j
class PptxTask extends TaskBase<Object> {

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    /** Action: READ | GENERATE | ADD_SLIDE | EXTRACT_TEXT */
    String action = 'GENERATE'

    /** Absolute path to the .pptx file. Required. */
    String filePath

    /** Optional master/theme template .pptx for GENERATE action. */
    String templatePath

    /** WorkingMemory key under which to store the task result. */
    String outputKey

    /**
     * Slide descriptors: List&lt;Map&gt; each with keys: title, content, layout, notes.
     * Takes precedence over slidesKey if both are set.
     *
     * Supported layout values: TITLE_CONTENT (default), TITLE_ONLY, BLANK, TWO_CONTENT.
     */
    List<Map<String, Object>> slides

    /**
     * WorkingMemory key to pull slide data from.
     * Expects a List&lt;Map&gt; in the same structure as slides.
     * Typically populated by a prior LlmTask with outputSchema set.
     */
    String slidesKey

    /**
     * The POI implementation injected by the server factory.
     * Signature: { String action, Map config -&gt; Object result }
     * Must be non-null at execution time.
     */
    Closure<Object> pptxCallFn

    /** Effect class: IDEMPOTENT (generates/overwrites same output file). */
    final EffectClass effectClass = EffectClass.IDEMPOTENT

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
        if (!filePath) {
            throw new IllegalStateException("PptxTask '${id}': filePath is required")
        }

        Map<String, Object> memory = resolveWorkingMemory(ctx)

        return ctx.promiseFactory.executeAsync {
            Map config = buildConfig(memory, prevValue)
            log.debug "PptxTask '${id}': executing action=${action} on ${filePath}"

            Object result = pptxCallFn.call(action.toUpperCase(), config)

            if (outputKey && result != null) {
                storeResult(ctx, outputKey, result)
            }

            log.info "PptxTask '${id}': completed, result type=${result?.getClass()?.simpleName}"
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

        if (templatePath) config.templatePath = templatePath

        // Resolve slides: explicit slides > slidesKey from memory > prevValue (if List)
        if (slides) {
            config.slides = slides
        } else if (slidesKey) {
            def memSlides = memory?.sharedFacts?.get(slidesKey) ?:
                            memory?.runContext?.get(slidesKey)
            if (memSlides instanceof List) {
                config.slides = memSlides
            } else if (prevValue instanceof List) {
                config.slides = prevValue
            }
        } else if (prevValue instanceof List) {
            config.slides = prevValue
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
                log.debug "PptxTask '${id}': stored result under sharedFacts['${key}']"
            }
        }
    }
}
