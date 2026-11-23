package org.softwood.promise

/**
 * Identifies a concrete Promise implementation family.
 *
 * <p>The {@link org.softwood.promise.core.PromiseConfiguration} uses this key
 * to return a matching {@code PromiseFactory}.</p>
 *
 * Extend this enum as you add other backends (e.g. Reactor, Rx, Akka, etc.).
 */
enum PromiseImplementation {
    DATAFLOW,
    VERTX,
    COMPLETABLE_FUTURE
}