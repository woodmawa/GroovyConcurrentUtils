// ═════════════════════════════════════════════════════════════
// ActorPatterns.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor

/**
 * Common actor patterns that work reliably with Groovy closures.
 * 
 * <p>This class provides tested, production-ready patterns for common
 * actor scenarios. These patterns avoid Groovy closure scoping issues
 * and demonstrate best practices.</p>
 * 
 * @since 1.0.0
 */
class ActorPatterns {
    
    /**
     * Pattern: Aggregator that collects results from multiple workers.
     * 
     * <h2>Use Case</h2>
     * Coordinate multiple worker actors and aggregate their results.
     * 
     * <h2>Example</h2>
     * <pre>
     * def aggregator = system.actor {
     *     name 'aggregator'
     *     onMessage ActorPatterns.aggregatorPattern(3) { results, ctx ->
     *         println "All done! Results: ${results}"
     *     }
     * }
     * 
     * // Workers send results
     * aggregator.tell([type: 'result', data: [count: 10]])
     * aggregator.tell([type: 'result', data: [count: 20]])
     * aggregator.tell([type: 'result', data: [count: 30]])
     * // Completion handler called with all results
     * </pre>
     * 
     * @param expectedCount number of results to wait for
     * @param completionHandler called when all results received
     * @return message handler closure
     */
    static Closure aggregatorPattern(int expectedCount, Closure completionHandler) {
        return { msg, ctx ->
            // Initialize state on first message
            if (!ctx.state.containsKey('results')) {
                ctx.state.results = []
                ctx.state.expected = expectedCount
            }
            
            if (msg.type == 'result') {
                ctx.state.results << msg.data
                
                if (ctx.state.results.size() >= ctx.state.expected) {
                    completionHandler.call(ctx.state.results, ctx)
                }
            }
        }
    }
    
    /**
     * Helper: Extract file extension safely.
     * 
     * @param filename the filename
     * @return the extension or 'no-extension'
     */
    static String getExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return 'no-extension'
        }
        
        int lastDot = filename.lastIndexOf('.')
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return 'no-extension'
        }
        
        return filename.substring(lastDot + 1).toLowerCase()
    }
    
    /**
     * Helper: Generate unique actor name.
     * 
     * @param baseName base name for the actor
     * @return unique name with UUID suffix
     */
    static String uniqueName(String baseName) {
        return "${baseName}-${UUID.randomUUID().toString().take(8)}"
    }
}
