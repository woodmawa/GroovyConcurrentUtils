package org.softwood.promise.core.vertx

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.vertx.core.AsyncResult
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.Callable
import java.util.function.Function

/**
 * <p>
 * Factory for creating {@link org.softwood.promise.Promise} instances backed by Vert.x 5.
 * </p>
 *
 * <h2>Purpose</h2>
 * <p>
 * This class provides a Vert.x-specific implementation of a Promise factory.
 * All promises returned are {@link VertxPromiseAdapter} instances wrapping Vert.x
 * {@link io.vertx.core.Future} objects.  It supports:
 * </p>
 *
 * <ul>
 *     <li>Creating incomplete, succeeded, and failed promises</li>
 *     <li>Executing Groovy closures asynchronously via Vert.x worker pool</li>
 *     <li>Parallel orchestration with {@code all()} and {@code any()}</li>
 *     <li>Sequential orchestration via {@code sequence()}</li>
 *     <li>Vert.x shutdown as a promise</li>
 * </ul>
 *
 * <h2>Vert.x 5 Compatibility</h2>
 * <p>
 * Vert.x 5 moved list-based composition APIs from {@code CompositeFuture.all/any}
 * to static helpers on {@link Future}.  Additionally, {@code CompositeFuture}
 * no longer supports {@code map()}, so all transformations must use
 * {@code transform(Function)} explicitly.  This class implements these changes
 * correctly so it compiles under Groovy 5's {@code @CompileStatic}.
 * </p>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * All created promises delegate to Vert.x’s thread-safe Future implementation.
 * Worker-thread dispatch is used for blocking closures.
 * </p>
 *
 * @author Softwood
 * @since 2025-01-20
 */
@Slf4j
@CompileStatic
class VertxPromiseFactory implements PromiseFactory {

    private final Vertx vertx

    /**
     * Constructs a new Vert.x-backed promise factory.
     *
     * @param vertx a non-null Vert.x instance used by all promises created
     * @throws IllegalArgumentException if {@code vertx} is null
     */
    VertxPromiseFactory(Vertx vertx) {
        if (vertx == null)
            throw new IllegalArgumentException("Vertx instance cannot be null")
        this.vertx = vertx
    }

    // -------------------------------------------------------------------------
    //  Basic construction
    // -------------------------------------------------------------------------

    /**
     * Creates an uncompleted promise.
     *
     * @param <T> result type
     * @return a new promise that may later be completed or failed
     */
    @Override
    <T> Promise<T> createPromise() {
        return VertxPromiseAdapter.<T>create(vertx)
    }

    /**
     * Creates a promise completed successfully with the supplied value.
     *
     * @param value the completed result value (may be null)
     * @param <T>   result type
     * @return a succeeded promise
     */
    @Override
    <T> Promise<T> createPromise(T value) {
        return VertxPromiseAdapter.succeededPromise(vertx, value)
    }

    /**
     * Creates a promise failed with the given exception.
     *
     * @param cause failure cause
     * @param <T>   result type (unused)
     * @return a failed promise
     */
    <T> Promise<T> createFailedPromise(Throwable cause) {
        return VertxPromiseAdapter.failedPromise(vertx, cause)
    }

    // -------------------------------------------------------------------------
    //  Worker-thread execution
    // -------------------------------------------------------------------------

    /**
     * Executes a blocking Groovy closure asynchronously on a Vert.x worker thread.
     *
     * <p>
     * This method is intended for CPU-bound or blocking work that must not run
     * on an event loop thread.
     * </p>
     *
     * @param task a closure producing a value
     * @param <T>  result type
     * @return a promise that completes with the closure’s result or failure
     */
    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        Callable<T> callable = { -> task.call() } as Callable<T>
        Future<T> f = vertx.executeBlocking(callable, false)
        return new VertxPromiseAdapter<T>(f, vertx)
    }

    // -------------------------------------------------------------------------
    //  Parallel composition: ALL
    // -------------------------------------------------------------------------

    /**
     * Executes a list of promises in parallel and returns a promise that
     * completes when <b>all</b> input promises complete successfully.
     *
     * <ul>
     *     <li>If all succeed → returns their results in order</li>
     *     <li>If any fail → returned promise fails with that error</li>
     * </ul>
     *
     * <p>
     * Vert.x 5 returns a {@link CompositeFuture} from {@link Future#all(List)},
     * so this method wraps it using {@code transform} to produce
     * {@code Future<List<T>>}.
     * </p>
     *
     * @param promises list of promises
     * @param <T> item type
     * @return promise completing with list of results
     */
    <T> Promise<List<T>> all(List<Promise<T>> promises) {
        if (!promises || promises.isEmpty()) {
            return createPromise(Collections.<T>emptyList())
        }

        List<Future<T>> futures = promises.collect { Promise<T> p ->
            ((VertxPromiseAdapter<T>) p).future
        }

        CompositeFuture cf =
                Future.all(futures as List<Future<?>>)

        Future<List<T>> listFuture = cf.transform(
                { AsyncResult<CompositeFuture> ar ->
                    if (ar.succeeded()) {
                        List<T> out = ar.result().<T>list()
                        return Future.succeededFuture(out)
                    }
                    return Future.failedFuture(ar.cause())
                } as Function<AsyncResult<CompositeFuture>, Future<List<T>>>
        )

        return new VertxPromiseAdapter<List<T>>(listFuture, vertx)
    }

    // -------------------------------------------------------------------------
    //  Parallel composition: ANY
    // -------------------------------------------------------------------------

    /**
     * Races a list of promises and returns the result of the first one
     * that succeeds.
     *
     * <ul>
     *     <li>If one promise succeeds → returns its value</li>
     *     <li>If all promises fail → returned promise fails with aggregated error</li>
     * </ul>
     *
     * @param promises list of promises to race
     * @param <T>      result type
     * @return promise containing the first successful result
     */
    <T> Promise<T> any(List<Promise<T>> promises) {
        if (!promises || promises.isEmpty()) {
            return createFailedPromise(
                    new IllegalArgumentException("Promise list cannot be empty")
            )
        }

        List<Future<T>> futures = promises.collect { Promise<T> p ->
            ((VertxPromiseAdapter<T>) p).future
        }

        CompositeFuture cf =
                Future.any(futures as List<Future<?>>)

        Future<T> firstFuture = cf.transform(
                { AsyncResult<CompositeFuture> ar ->
                    if (ar.succeeded()) {
                        CompositeFuture c = ar.result()
                        for (int i = 0; i < c.size(); i++) {
                            if (c.succeeded(i)) {
                                T v = c.<T>resultAt(i)
                                return Future.succeededFuture(v)
                            }
                        }
                        // Should not occur
                        return Future.failedFuture(
                                new IllegalStateException("any() succeeded, but no succeeded index found")
                        )
                    }
                    return Future.failedFuture(ar.cause())
                } as Function<AsyncResult<CompositeFuture>, Future<T>>
        )

        return new VertxPromiseAdapter<T>(firstFuture, vertx)
    }

    // -------------------------------------------------------------------------
    //  Sequential chaining
    // -------------------------------------------------------------------------

    /**
     * Runs a list of closures sequentially, threading each result into the next.
     *
     * <p>
     * This behaves like a fold/left-reduce, where each closure has signature:
     * {@code T call(T previous)}.
     * </p>
     *
     * @param initial starting value
     * @param tasks   tasks applied sequentially
     * @param <T>     accumulated type
     * @return promise completing with final accumulated value
     */
    <T> Promise<T> sequence(T initial, List<Closure<T>> tasks) {
        if (!tasks || tasks.isEmpty()) {
            return createPromise(initial)
        }

        Future<T> chain = Future.succeededFuture(initial)

        tasks.each { Closure<T> task ->
            chain = chain.compose(
                    { T prev ->
                        Callable<T> callable = { -> task.call(prev) } as Callable<T>
                        vertx.executeBlocking(callable, false)
                    } as Function<T, Future<T>>
            )
        }

        return new VertxPromiseAdapter<T>(chain, vertx)
    }

    // -------------------------------------------------------------------------
    //  Shutdown
    // -------------------------------------------------------------------------

    /**
     * Closes the Vert.x instance and returns a promise completing when fully closed.
     *
     * @return a promise that resolves when Vert.x shutdown completes
     */
    Promise<Void> shutdown() {
        Future<Void> cf = vertx.close()
        return new VertxPromiseAdapter<Void>(cf, vertx)
    }

    /**
     * Provides the Vert.x instance used by this factory.
     *
     * @return Vert.x instance
     */
    Vertx getVertx() {
        return vertx
    }
}
