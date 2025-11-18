package org.softwood.promise.core.vertx

import groovy.util.logging.Slf4j
import io.vertx.core.Vertx
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory

/**
 * Vert.x-based implementation of PromiseFactory
 */
@Slf4j
class VertxPromiseFactory implements PromiseFactory {
    private final Vertx vertx

    VertxPromiseFactory(Vertx vertx) {
        this.vertx = vertx
    }

    @Override
    <T> Promise<T> createPromise() {
        def promise = VertxPromise.<T>promise()
        return new VertxPromiseAdapter<T>(promise.future(), vertx)
    }

    @Override
    <T> Promise<T> createPromise(T value) {
        return new VertxPromiseAdapter<T>(io.vertx.core.Future.succeededFuture(value), vertx)
    }

    @Override
    <T> Promise<T> executeAsync(Closure<T> task) {
        def promise = VertxPromise.<T>promise()

        vertx.executeBlocking({ promiseHandler ->
            try {
                T result = task.call()
                promiseHandler.complete(result)
            } catch (Throwable e) {
                promiseHandler.fail(e)
            }
        }, false)
                .onComplete { ar ->
                    if (ar.succeeded()) {
                        promise.complete(ar.result())
                    } else {
                        promise.fail(ar.cause())
                    }
                }

        return new VertxPromiseAdapter<T>(promise.future(), vertx)
    }
}
