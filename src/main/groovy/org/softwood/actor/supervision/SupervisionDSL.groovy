package org.softwood.actor.supervision
/**
 * Lightweight supervision utilities - opt-in, not mandatory.
 *
 * Three approaches:
 *  1. Wrapper functions (simplest)
 *  2. Supervision trait (optional mixin)
 *  3. Full ActorSupervisor (advanced users)
 */
// ═════════════════════════════════════════════════════════════
// Approach 1: Simple Wrapper Functions (RECOMMENDED)
// ═════════════════════════════════════════════════════════════

class SupervisionDSL {

    /**
     * Wrap a handler with automatic error forwarding.
     *
     * Usage:
     *   onMessage supervise("ErrorHandler") { msg, ctx ->
     *       riskyOperation(msg)
     *   }
     */
    static Closure supervise(String supervisorName, Closure handler) {
        return { msg, ctx ->
            try {
                handler.call(msg, ctx)
            } catch (Exception e) {
                ctx.forward(supervisorName, [
                        actor: ctx.actorName,
                        error: e.message,
                        cause: e.class.simpleName,
                        message: msg,
                        timestamp: System.currentTimeMillis()
                ])
                // Re-throw so ask messages get proper error
                if (ctx.isAskMessage()) {
                    throw e
                }
            }
        }
    }

    /**
     * Wrap with retry logic.
     *
     * Usage:
     *   onMessage retry(3) { msg, ctx ->
     *       unstableOperation(msg)
     *   }
     */
    static Closure retry(int maxAttempts, Closure handler) {
        return { msg, ctx ->
            int attempt = 0
            Exception lastError = null

            while (attempt < maxAttempts) {
                try {
                    return handler.call(msg, ctx)
                } catch (Exception e) {
                    lastError = e
                    attempt++
                    if (attempt < maxAttempts) {
                        Thread.sleep(100 * attempt) // Exponential backoff
                    }
                }
            }

            throw new RuntimeException("Failed after $maxAttempts attempts", lastError)
        }
    }

    /**
     * Wrap with timeout.
     *
     * Usage:
     *   onMessage timeout(5000) { msg, ctx ->
     *       longRunningOperation(msg)
     *   }
     */
    static Closure timeout(long millis, Closure handler) {
        return { msg, ctx ->
            def future = java.util.concurrent.CompletableFuture.supplyAsync {
                handler.call(msg, ctx)
            }

            try {
                return future.get(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true)
                throw new RuntimeException("Operation timed out after ${millis}ms")
            }
        }
    }

    /**
     * Combine multiple wrappers.
     *
     * Usage:
     *   onMessage compose(
     *       retry(3),
     *       timeout(5000),
     *       supervise("ErrorHandler")
     *   ) { msg, ctx ->
     *       operation(msg)
     *   }
     */
    static Closure compose(List<Closure> wrappers, Closure handler) {
        Closure result = handler
        wrappers.reverse().each { wrapper ->
            result = wrapper.call(result)
        }
        return result
    }
}
