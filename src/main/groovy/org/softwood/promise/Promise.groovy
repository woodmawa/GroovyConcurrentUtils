package org.softwood.promise

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * Core Promise interface that all implementations must follow
 */
interface Promise<T> {
    Promise<T> accept (T value)
    Promise<T> accept (Supplier<T> value)
    T get() throws Exception
    T get(long timeout, TimeUnit unit) throws TimeoutException
    boolean isDone()
    Promise<T> onComplete(Consumer<T> callback)
    <R> Promise<R> then(Function<T, R> function)
    Promise<T> onError(Consumer<Throwable> errorHandler)
    <R> Promise<R> recover(Function<Throwable, R> recovery)
    CompletableFuture<T> asType (CompletableFuture)
}