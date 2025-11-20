package org.softwood.promise

import groovy.util.logging.Slf4j
import org.softwood.promise.core.PromiseConfiguration

/**
 * Main entry point for creating promises with pluggable implementations
 */
@Slf4j
class Promises {

    /**
     * Create a new promise using default implementation
     */
    static <T> Promise<T> newPromise() {
        return PromiseConfiguration.getFactory().createPromise()
    }

    /**
     * Create a promise with an initial value using default implementation
     */
    static <T> Promise<T> newPromise(T value) {
        return PromiseConfiguration.getFactory().createPromise(value)
    }

    /**
     * Create a promise using specific implementation
     */
    static <T> Promise<T> newPromise(PromiseImplementation impl) {
        return PromiseConfiguration.getFactory(impl).createPromise()
    }

    /**
     * Create a promise with value using specific implementation
     */
    static <T> Promise<T> newPromise(PromiseImplementation impl, T value) {
        return PromiseConfiguration.getFactory(impl).createPromise(value)
    }

    /**
     * Execute async task using default implementation
     */
    static <T> Promise<T> async(Closure<T> task) {
        return PromiseConfiguration.getFactory().executeAsync(task)
    }

    /**
     * Execute async task using specific implementation
     */
    static <T> Promise<T> async(PromiseImplementation impl, Closure<T> task) {
        return PromiseConfiguration.getFactory(impl).executeAsync(task)
    }
}
