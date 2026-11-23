package org.softwood.dag.task

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Instant
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * A DAG task that performs a single-shot LLM inference call.
 *
 * <p><b>Library contract:</b> this class is MCP/HTTP-agnostic. It depends only on
 * the GroovyConcurrentUtils library. The server factory injects the actual LLM
 * call implementation via {@link #llmCallFn}.</p>
 *
 * <h3>Params (set by FlowBuilder from FlowDescriptor):</h3>
 * <pre>
 *   systemPrompt        – Optional system-role message.
 *   userPromptTemplate  – Template string; {@code ${varName}} tokens are resolved
 *                         from working memory before the call.
 *   modelTier           – Routing hint string: "SIMPLE" | "MODERATE" | "CRITICAL".
 *                         Default: "MODERATE".
 *   temperature         – Float passed to the orchestrator. Default: 0.3.
 *   maxParseRetries     – How many times to retry with corrective prompt if JSON
 *                         parsing fails. Default: 1.
 *   outputSchema        – Optional Map (JSON Schema shape) describing the expected
 *                         JSON structure of the LLM response. When supplied, the task
 *                         parses and validates the raw response before storing it.
 * </pre>
 *
 * <h3>Result:</h3>
 * <p>Stored as {@code Map} (parsed JSON) when {@code outputSchema} is set,
 * or as {@code String} (raw response) otherwise. Written into
 * {@code ctx.globals["__workingMemory"].resultsByNode[id]} by FlowBuilder wrapper.</p>
 *
 * <h3>Injection:</h3>
 * <pre>
 *   task.llmCallFn = { String systemPrompt, String userPrompt, String modelTier,
 *                      float temperature -> String rawResponse }
 * </pre>
 *
 * <h3>Effect class:</h3>
 * PURE – safe to retry; no side effects beyond token consumption.
 */
@Slf4j
class LlmTask extends TaskBase<Object> {

    // -------------------------------------------------------------------------
    // Config (set by server factory after construction)
    // -------------------------------------------------------------------------

    /** System prompt text; may be null. */
    String systemPrompt

    /** User prompt template; supports {@code ${varName}} interpolation from globals. */
    String userPromptTemplate = ""

    /** Routing hint for the orchestrator: SIMPLE | MODERATE | CRITICAL. */
    String modelTier = "MODERATE"

    /** Sampling temperature. */
    float temperature = 0.3f

    /** Times to re-prompt with corrective instruction if JSON parse fails. */
    int maxParseRetries = 1

    /**
     * Optional JSON Schema (as Groovy Map) that describes the expected response.
     * When set, the raw LLM string is parsed as JSON and validated against this schema.
     * If null, the raw string is returned as-is.
     */
    Map<String, Object> outputSchema = null

    /**
     * The LLM call implementation injected by the server factory.
     *
     * Signature: {@code Closure<String>(String systemPrompt, String userPrompt,
     *                                   String modelTier, float temperature)}
     *
     * Must be non-null at execution time.
     */
    Closure<String> llmCallFn

    /**
     * The effect class for this task type – always PURE for LLM inference.
     * Server-side scope management reads this to decide retry safety.
     */
    final EffectClass effectClass = EffectClass.PURE

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    LlmTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // -------------------------------------------------------------------------
    // Core execution
    // -------------------------------------------------------------------------

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevResult) {
        if (!llmCallFn) {
            throw new IllegalStateException("LlmTask '${id}': llmCallFn has not been injected by the server factory")
        }

        // 1. Resolve the user prompt template against working memory
        Map<String, Object> memory = resolveWorkingMemory(ctx)
        String resolvedPrompt = resolveTemplate(userPromptTemplate, memory, prevResult)
        log.debug "LlmTask '${id}': resolved prompt (${resolvedPrompt.length()} chars)"

        // 2. Invoke the LLM asynchronously (with parse-retry loop)
        return ctx.promiseFactory.executeAsync {
            Object result = callWithParseRetry(systemPrompt, resolvedPrompt)
            log.info "LlmTask '${id}': completed, result type=${result?.getClass()?.simpleName}"
            result
        }
    }

    // -------------------------------------------------------------------------
    // Template resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves {@code ${varName}} tokens in the template.
     *
     * Resolution order:
     *   1. Dot-path into working memory resultsByNode   (e.g. {@code ${run-build.healthy}})
     *   2. sharedFacts map in working memory
     *   3. runContext map in working memory
     *   4. {@code ctx.globals} directly
     *   5. Leaves unreplaced if not found (with a WARN)
     *
     * The special token {@code ${prev}} resolves to {@code prevResult.toString()}.
     */
    private String resolveTemplate(String template, Map<String, Object> memory, Object prevResult) {
        if (!template) return ""
        Pattern TOKEN = Pattern.compile(/\$\{([^}]+)\}/)
        Matcher m = TOKEN.matcher(template)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            String key = m.group(1)
            String value = resolveToken(key, memory, prevResult)
            m.appendReplacement(sb, Matcher.quoteReplacement(value))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    private String resolveToken(String key, Map<String, Object> memory, Object prevResult) {
        // Special case: ${prev}
        if (key == 'prev') {
            return prevResult != null ? prevResult.toString() : ''
        }

        // Dot-path into resultsByNode (e.g. "run-build.healthy")
        if (key.contains('.')) {
            def parts = key.tokenize('.')
            String nodeId = parts[0]
            List<String> path = parts.tail()
            def nodeResult = memory?.resultsByNode?.get(nodeId)?.value
            if (nodeResult != null) {
                def val = deepGet(nodeResult, path)
                if (val != null) return val.toString()
            }
        }

        // sharedFacts
        def shared = memory?.sharedFacts?.get(key)
        if (shared != null) return shared.toString()

        // runContext
        def rc = memory?.runContext?.get(key)
        if (rc != null) return rc.toString()

        // ctx.globals fallback
        def global = ctx?.globals?.get(key)
        if (global != null) return global.toString()

        log.warn "LlmTask '${id}': unresolved template token '\${${key}}'"
        return "\${${key}}"
    }

    private Object deepGet(Object obj, List<String> path) {
        def current = obj
        for (String key : path) {
            if (current instanceof Map) {
                current = current[key]
            } else {
                return null
            }
        }
        return current
    }

    private Map<String, Object> resolveWorkingMemory(TaskContext taskCtx) {
        def wm = taskCtx?.globals?.get('__workingMemory')
        return wm instanceof Map ? (Map<String, Object>) wm : [:]
    }

    // -------------------------------------------------------------------------
    // LLM call with parse-retry
    // -------------------------------------------------------------------------

    private Object callWithParseRetry(String sysPrompt, String userPrompt) {
        String raw = llmCallFn.call(sysPrompt, userPrompt, modelTier, temperature)

        if (!outputSchema) {
            // No schema → return raw string
            return raw
        }

        // Attempt JSON parse
        for (int attempt = 0; attempt <= maxParseRetries; attempt++) {
            try {
                Object parsed = new JsonSlurper().parseText(raw)
                // Basic structural validation (type check only in v1)
                validateAgainstSchema(parsed, outputSchema)
                return parsed
            } catch (Exception e) {
                if (attempt >= maxParseRetries) {
                    log.error "LlmTask '${id}': JSON parse failed after ${attempt + 1} attempt(s): ${e.message}"
                    throw new LlmParseException("LlmTask '${id}' could not parse LLM response as JSON: ${e.message}", e, raw)
                }
                // Build corrective prompt
                log.warn "LlmTask '${id}': JSON parse failed (attempt ${attempt + 1}), retrying with corrective prompt"
                String corrective = buildCorrectivePrompt(raw, outputSchema, e.message)
                raw = llmCallFn.call(sysPrompt, corrective, modelTier, temperature)
            }
        }
        // Unreachable, but Groovy needs a return
        return raw
    }

    /**
     * Very lightweight schema validation: checks top-level type and presence of
     * required properties if declared. Full JSON Schema validation deferred to v2.
     */
    private void validateAgainstSchema(Object parsed, Map<String, Object> schema) {
        String expectedType = schema?.get('type')
        if (expectedType == 'object' && !(parsed instanceof Map)) {
            throw new LlmParseException("Expected JSON object, got ${parsed?.getClass()?.simpleName}", null, null)
        }
        if (expectedType == 'array' && !(parsed instanceof List)) {
            throw new LlmParseException("Expected JSON array, got ${parsed?.getClass()?.simpleName}", null, null)
        }
        List<String> required = schema?.get('required') as List<String>
        if (required && parsed instanceof Map) {
            List<String> missing = required.findAll { !(parsed as Map).containsKey(it) }
            if (missing) {
                throw new LlmParseException("Missing required fields: ${missing}", null, null)
            }
        }
    }

    private String buildCorrectivePrompt(String badResponse, Map outputSchema, String parseError) {
        return """Your previous response could not be parsed as valid JSON.
Parse error: ${parseError}
Expected schema: ${JsonOutput.toJson(outputSchema)}
Your previous response was:
${badResponse}

Please respond ONLY with valid JSON matching the schema. No preamble, no explanation, no markdown code fences."""
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    static class LlmParseException extends RuntimeException {
        final String rawResponse

        LlmParseException(String message, Throwable cause, String rawResponse) {
            super(message, cause)
            this.rawResponse = rawResponse
        }
    }
}
