package org.softwood.promise.core.vertx

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import io.vertx.core.AsyncResult
import io.vertx.core.CompositeFuture
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.Promise as VertxPromise
import io.vertx.core.Future as VertxFuture

import org.softwood.promise.Promise as SoftPromise
import org.softwood.promise.PromiseFactory

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import java.util.function.Supplier

/**
 * <h2>Vert.x 5 Promise Factory</h2>
 *
 * <p>
 * Implementation of {@link org.softwood.promise.PromiseFactory} producing
 * {@link VertxPromiseAdapter} instances backed by Vert.x 5.x {@link VertxFuture}
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
 *   <li>Transformations must use {@link VertxFuture#transform(Function)}.</li>
 *   <li>All owned promise completions occur within a Vert.x context.</li>
 * </ul>
 */
@Slf4j
@CompileStatic
class VertxPromiseFactory implements PromiseFactory {

    private final Vertx vertx

    VertxPromiseFactory(Vertx vertx) {
        if (vertx == null)
            throw new IllegalArgumentException("Vertx instance cannot be null")
        this.vertx = vertx
    }

    // -------------------------------------------------------------------------
    //  Basic SoftPromise Creation
    // -------------------------------------------------------------------------

    @Override
    def <T> SoftPromise<T> createPromise() {
        return VertxPromiseAdapter.<T>create(vertx)
    }

    @Override
    def <T> SoftPromise<T> createPromise(T value) {
        return VertxPromiseAdapter.succeededPromise(vertx, value)
    }

    @Override
    def <T> SoftPromise<T> createFailedPromise(Throwable cause) {
        return VertxPromiseAdapter.failedPromise(vertx, cause)
    }

    // -------------------------------------------------------------------------
    //  executeAsync — Groovy Closure
    // -------------------------------------------------------------------------

    @Override
    def <T> SoftPromise<T> executeAsync(Closure<T> task) {

        Callable<T> callable = { -> task.call() } as Callable<T>

        VertxFuture<T> f = vertx.executeBlocking(callable, false)

        return new VertxPromiseAdapter<T>(f, vertx)
    }

    // -------------------------------------------------------------------------
    //  executeAsync — Callable<T>
    // -------------------------------------------------------------------------

    @Override
    def <T> SoftPromise<T> executeAsync(Callable<T> task) {

        VertxFuture<T> f = vertx.executeBlocking(task, false)

        return new VertxPromiseAdapter<T>(f, vertx)
    }

    // -------------------------------------------------------------------------
    //  executeAsync — Runnable
    // -------------------------------------------------------------------------

    @Override
    SoftPromise<Void> executeAsync(Runnable task) {

        Callable<Void> callable = { ->
            task.run()
            return null
        } as Callable<Void>

        VertxFuture<Void> f = vertx.executeBlocking(callable, false)

        return new VertxPromiseAdapter<Void>(f, vertx)
    }

    // -------------------------------------------------------------------------
    //  executeAsync — Supplier<T>
    // -------------------------------------------------------------------------

    @Override
    def <T> SoftPromise<T> executeAsync(Supplier<T> supplier) {

        Callable<T> callable = { -> supplier.get() } as Callable<T>

        VertxFuture<T> f = vertx.executeBlocking(callable, false)

        return new VertxPromiseAdapter<T>(f, vertx)
    }

    // -------------------------------------------------------------------------
    //  executeAsync — Function<T,R>
    // -------------------------------------------------------------------------

    @Override
    def <T, R> SoftPromise<R> executeAsync(Function<T, R> fn, T input) {

        Callable<R> callable = { -> fn.apply(input) } as Callable<R>

        VertxFuture<R> f = vertx.executeBlocking(callable, false)

        return new VertxPromiseAdapter<R>(f, vertx)
    }

    // -------------------------------------------------------------------------
    //  CompletableFuture SUPPORT
    // -------------------------------------------------------------------------

    @Override
    def <T> SoftPromise<T> from(CompletableFuture<T> future) {
        VertxPromiseAdapter<T> adapter = VertxPromiseAdapter.<T>create(vertx)
        adapter.accept(future)
        return adapter
    }

    @Override
    def <T> SoftPromise<T> from(SoftPromise<T> otherPromise) {
        VertxPromiseAdapter<T> adapter = VertxPromiseAdapter.<T>create(vertx)
        adapter.accept(otherPromise)
        return adapter
    }

    // -------------------------------------------------------------------------
    //  Parallel ALL
    // -------------------------------------------------------------------------

    def <T> SoftPromise<List<T>> all(List<SoftPromise<T>> promises) {

        if (!promises || promises.isEmpty()) {
            return createPromise(Collections.<T>emptyList())
        }

        List<VertxFuture<T>> futures = promises.collect { SoftPromise<T> p ->
            ((VertxPromiseAdapter<T>) p).future
        }

        VertxFuture<CompositeFuture> cf =
                VertxFuture.all(futures as List<VertxFuture<?>>)

        VertxFuture<List<T>> listFuture =
                cf.compose({ CompositeFuture comp ->
                    List<T> out = comp.<T>list()
                    VertxFuture.succeededFuture(out)
                })

        return new VertxPromiseAdapter<List<T>>(listFuture, vertx)
    }

    // -------------------------------------------------------------------------
    //  Parallel ANY
    // -------------------------------------------------------------------------

    def <T> SoftPromise<T> any(List<SoftPromise<T>> promises) {

        if (!promises || promises.isEmpty()) {
            return createFailedPromise(new IllegalArgumentException("Promise list cannot be empty"))
        }

        List<VertxFuture<T>> futures = promises.collect { SoftPromise<T> p ->
            ((VertxPromiseAdapter<T>) p).future
        }

        VertxFuture<CompositeFuture> cf =
                VertxFuture.any(futures as List<VertxFuture<?>>)

        VertxFuture<T> firstFuture =
                cf.compose({ CompositeFuture comp ->
                    for (int i = 0; i < comp.size(); i++) {
                        if (comp.succeeded(i)) {
                            return VertxFuture.succeededFuture(comp.<T>resultAt(i))
                        }
                    }
                    return VertxFuture.failedFuture(
                            new IllegalStateException("any() succeeded but no succeeded index found")
                    )
                }) as VertxFuture<T>

        return new VertxPromiseAdapter<T>(firstFuture, vertx)
    }

    // -------------------------------------------------------------------------
    //  Sequence
    // -------------------------------------------------------------------------

    def <T> SoftPromise<T> sequence(T initial, List<Closure<T>> tasks) {

        if (!tasks || tasks.isEmpty()) {
            return createPromise(initial)
        }

        VertxFuture<T> chain = VertxFuture.succeededFuture(initial)

        tasks.each { Closure<T> task ->
            chain = chain.compose({ T prev ->
                Callable<T> callable = { -> task.call(prev) } as Callable<T>
                vertx.executeBlocking(callable, false)
            })
        }

        return new VertxPromiseAdapter<T>(chain, vertx)
    }

    // -------------------------------------------------------------------------
    //  Shutdown
    // -------------------------------------------------------------------------

    SoftPromise<Void> shutdown() {
        VertxFuture<Void> closing = vertx.close()
        return new VertxPromiseAdapter<Void>(closing, vertx)
    }

    Vertx getVertx() {
        return vertx
    }
}