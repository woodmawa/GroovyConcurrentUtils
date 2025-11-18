package org.softwood.promise
package org.softwood.dataflow.promise

import groovy.util.logging.Slf4j
import io.vertx.core.Vertx
import io.vertx.core.Promise as VertxPromise
import org.softwood.dataflow.core.DataFlowVariable
import org.softwood.dataflow.core.DataFlowFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function

/**
 * Core Promise interface that all implementations must follow
 */
interface Promise<T> {
    T get() throws Exception
    T get(long timeout, TimeUnit unit) throws TimeoutException
    boolean isDone()
    Promise<T> onComplete(Consumer<T> callback)
    <R> Promise<R> then(Function<T, R> function)
    Promise<T> onError(Consumer<Throwable> errorHandler)
    <R> Promise<R> recover(Function<Throwable, R> recovery)
}