package org.softwood.promise

import groovy.util.logging.Slf4j
import org.softwood.promise.core.PromiseConfiguration

import java.util.concurrent.CompletableFuture

/**
 * Static facade and main entry point for creating Promises.
 *
 * <p>This class delegates to {@link PromiseConfiguration} to obtain the
 * active {@link PromiseFactory} and is intended to be the user-facing API.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * def p = Promises.newPromise()
 * p.accept(42)
 *
 * Promises.async { expensiveWork() }
 *     .then { it * 2 }
 *     .onComplete { println it }
 * </pre>
 */
@Slf4j
class Promises {

    /**
     * Create a new promise using the default implementation.
     *
     * @param <T> value type
     * @return a new uncompleted promise
     */
    static <T> Promise<T> newPromise() {
        return PromiseConfiguration.getFactory().createPromise()
    }

    /**
     * Create a new promise already completed with a value using default implementation.
     *
     * @param value initial completion value
     * @param <T> value type
     * @return completed promise
     */
    static <T> Promise<T> newPromise(T value) {
        return PromiseConfiguration.getFactory().createPromise(value)
    }

    /**
     * Create a new promise using a specific implementation.
     *
     * @param impl implementation key
     * @param <T> value type
     * @return new uncompleted promise
     */
    static <T> Promise<T> newPromise(PromiseImplementation impl) {
        return PromiseConfiguration.getFactory(impl).createPromise()
    }

    /**
     * Create a new promise already completed with a value using a specific implementation.
     *
     * @param impl implementation key
     * @param value completion value
     * @param <T> value type
     * @return completed promise
     */
    static <T> Promise<T> newPromise(PromiseImplementation impl, T value) {
        return PromiseConfiguration.getFactory(impl).createPromise(value)
    }

    /**
     * Execute async task using default implementation.
     *
     * @param task closure to run async
     * @param <T> return type
     * @return a promise bound to the task result
     */
    static <T> Promise<T> async(Closure<T> task) {
        return PromiseConfiguration.getFactory().executeAsync(task)
    }

    /**
     * Execute async task using specific implementation.
     *
     * @param impl implementation key
     * @param task closure to run async
     * @param <T> return type
     * @return promise bound to result
     */
    static <T> Promise<T> async(PromiseImplementation impl, Closure<T> task) {
        return PromiseConfiguration.getFactory(impl).executeAsync(task)
    }

    /**
     * Adapt a CompletableFuture into a Promise using default implementation.
     *
     * @param future source future
     * @param <T> type of value
     * @return new Promise wired to future completion
     */
    static <T> Promise<T> from(CompletableFuture<T> future) {
        return PromiseConfiguration.getFactory().from(future)
    }

    /**
     * Adapt another Promise into a Promise using default implementation.
     * Useful when mixing implementations.
     *
     * @param otherPromise source promise
     * @param <T> type of value
     * @return new Promise wired to otherPromise completion
     */
    static <T> Promise<T> from(Promise<T> otherPromise) {
        return PromiseConfiguration.getFactory().from(otherPromise)
    }
}
