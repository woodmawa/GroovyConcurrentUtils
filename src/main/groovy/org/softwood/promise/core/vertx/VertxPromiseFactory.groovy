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
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/**
 * <h2>Vert.x 5 Promise Factory</h2>
 *
 * <p>
 * Implementation of {@link org.softwood.promise.PromiseFactory} producing
 * {@link VertxPromiseAdapter} instances backed by Vert.x 5.x {@link Future}
 * and {@link io.vertx.core.Promise}.
 * </p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Creating incomplete, succeeded, and failed promises</li>
 *   <li>Wrapping Java {@link CompletableFuture}</li>
 *   <li>Wrapping other {@link org.softwood.promise.Promise} instances</li>
 *   <li>Executing closures asynchronously on Vert.x worker threads</li>
 *   <li>Parallel orchestration: all(), any()</li>
 *   <li>Sequential orchestration: sequence()</li>
 *   <li>Providing a shutdown promise</li>
 * </ul>
 *
 * <h3>Vert.x 5 Compatibility Notes</h3>
 * <ul>
 *   <li>{@code CompositeFuture.all/any} now returns {@code Future&lt;CompositeFuture&gt;}.</li>
 *   <li>Transformations must use {@link Future#transform(Function)}.</li>
 *   <li>All owned promise completions occur within a Vert.x context.</li>
 * </ul>
 */
@Slf4j
@CompileStatic
class VertxPromiseFactory implements PromiseFactory {

    private final Vertx vertx

    /**
     * Constructs a Vert.x backed promise factory.
     *
     * @param vertx non-null Vert.x instance shared by all promises created
     * @throws IllegalArgumentException if vertx is null
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
     * Create a new uncompleted promise backed by Vert.x.
     */
    @Override
    <T> Promise<T> createPromise() {
        return VertxPromiseAdapter.<T>create(vertx)
    }

    /**
     * Create a promise completed successfully with a value.
     */
    @Override
    <T> Promise<T> createPromise(T value) {
        return VertxPromiseAdapter.succeededPromise(vertx, value)
    }

    /**
     * INTERNAL: create a Vert.x failed promise.
     */
    <T> Promise<T> createFailedPromise(Throwable cause) {
        return VertxPromiseAdapter.failedPromise(vertx, cause)
    }

    // -------------------------------------------------------------------------
    //  Worker-thread execution
    // -------------------------------------------------------------------------

    /**
     * Executes a closure asynchronously using Vert.x worker pool.
     *
     * <p>
     * Use this method for CPU-heavy or blocking operations.
     * </p>
     *
     * @param task closure to execute
     * @param <T> result type
     */
    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        Callable<T> callable = { -> task.call() } as Callable<T>
        Future<T> f = vertx.executeBlocking(callable, false)
        return new VertxPromiseAdapter<T>(f, vertx)
    }

    // -------------------------------------------------------------------------
    //  from(CompletableFuture<T>)
    // -------------------------------------------------------------------------

    /**
     * Adapts a Java {@link CompletableFuture} into a Vert.x-backed promise.
     *
     * <p>
     * A new {@link VertxPromiseAdapter} is created, and the completion of the
     * {@code CompletableFuture} is forwarded into Vert.x via the adapter’s
     * {@code accept(CompletableFuture)} method.  Completion always occurs
     * on a Vert.x context.
     * </p>
     *
     * @param future Java CompletableFuture
     * @param <T>    value type
     * @return a promise completing with the same result as the future
     */
    @Override
    def <T> Promise<T> from(CompletableFuture<T> future) {
        VertxPromiseAdapter<T> adapter = VertxPromiseAdapter.<T>create(vertx)
        adapter.accept(future)
        return adapter
    }

    // -------------------------------------------------------------------------
    //  from(Promise<T>)
    // -------------------------------------------------------------------------

    /**
     * Adapts another {@link org.softwood.promise.Promise} instance into a new
     * Vert.x-backed promise.
     *
     * <p>
     * A brand new {@link VertxPromiseAdapter} is created and bound to the
     * supplied SoftPromise via {@code accept(Promise)}.  The resulting promise
     * uses Vert.x for all continuations.
     * </p>
     *
     * @param otherPromise the source SoftPromise to adapt
     * @param <T> value type
     * @return a Vert.x-backed promise that completes with the same result
     */
    @Override
    def <T> Promise<T> from(Promise<T> otherPromise) {
        VertxPromiseAdapter<T> adapter = VertxPromiseAdapter.<T>create(vertx)
        adapter.accept(otherPromise)
        return adapter
    }

    // -------------------------------------------------------------------------
    //  Parallel composition: ALL
    // -------------------------------------------------------------------------

    /**
     * Execute several promises in parallel and complete when all succeed.
     *
     * @param promises list of promises
     * @param <T>      value type
     * @return promise completing with ordered list of results
     */
    <T> Promise<List<T>> all(List<Promise<T>> promises) {
        if (!promises || promises.isEmpty()) {
            return createPromise(Collections.<T>emptyList())
        }

        List<Future<T>> futures = promises.collect { Promise<T> p ->
            ((VertxPromiseAdapter<T>) p).future
        }

        Future<CompositeFuture> cf = Future.all(futures as List<Future<?>>)

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
     * Completes with the first successfully completed promise.
     *
     * @param promises list to race
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

        Future<CompositeFuture> cf = Future.any(futures as List<Future<?>>)

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
                        return Future.failedFuture(
                                new IllegalStateException(
                                        "any() succeeded without any succeeded index"
                                )
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
     * Sequentially runs a list of closures, each receiving the output of the
     * previous one.  All steps execute using Vert.x worker threads.
     *
     * @param initial initial value
     * @param tasks   closures of type {@code T call(T)}
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
    //  Vert.x Shutdown
    // -------------------------------------------------------------------------

    /**
     * Shuts down the Vert.x instance and returns a promise that completes
     * when the shutdown is fully finished.
     */
    Promise<Void> shutdown() {
        Future<Void> closing = vertx.close()
        return new VertxPromiseAdapter<Void>(closing, vertx)
    }

    /**
     * Returns the Vert.x instance this factory was constructed with.
     */
    Vertx getVertx() {
        return vertx
    }
}
