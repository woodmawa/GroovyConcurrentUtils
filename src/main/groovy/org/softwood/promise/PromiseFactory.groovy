package org.softwood.promise

/**
 * Factory interface for creating promises
 */
interface PromiseFactory {
    <T> Promise<T> createPromise()
    <T> Promise<T> createPromise(T value)
    <T> Promise<T> executeAsync(Closure<T> task)
}
