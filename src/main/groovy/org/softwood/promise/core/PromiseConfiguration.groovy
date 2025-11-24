package org.softwood.promise.core

import groovy.util.logging.Slf4j
import io.vertx.core.Vertx
import org.softwood.dataflow.DataflowFactory
import org.softwood.promise.PromiseFactory
import org.softwood.promise.PromiseImplementation
import org.softwood.promise.core.cfuture.CompletableFuturePromiseFactory
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import org.softwood.promise.core.vertx.VertxPromiseFactory

import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for Promise factories.
 *
 * <p>Promises are created through this configuration to preserve the ability
 * to swap implementations at runtime or by environment.</p>
 *
 * <p>Typical usage is indirect via {@link org.softwood.promise.Promises}.</p>
 */
@Slf4j
class PromiseConfiguration {
    private static PromiseImplementation defaultImplementation = PromiseImplementation.DATAFLOW
    private static final Map<PromiseImplementation, PromiseFactory> factories = new ConcurrentHashMap<>()

    // Vertx instance for Vertx promises (lazy initialization)
    private static Vertx vertxInstance

    /**
     * static initialiser
     */
    static {
        // Register default factories
        registerFactory(PromiseImplementation.DATAFLOW, new DataflowPromiseFactory(new DataflowFactory()))
        registerFactory(PromiseImplementation.COMPLETABLE_FUTURE, new CompletableFuturePromiseFactory())
        registerFactory(PromiseImplementation.VERTX, new VertxPromiseFactory(vertxInstance))
    }

    /**
     * Set the default promise implementation
     */
    static void setDefaultImplementation(PromiseImplementation impl) {
        log.info "Setting default promise implementation to: $impl"
        defaultImplementation = impl

        // Ensure the factory is registered
        if (!factories.containsKey(impl)) {
            switch (impl) {
                case PromiseImplementation.VERTX:
                    if (!vertxInstance) {
                        vertxInstance = Vertx.vertx()
                    }
                    registerFactory(impl, new VertxPromiseFactory(vertxInstance))
                    break
                case PromiseImplementation.COMPLETABLE_FUTURE:
                    registerFactory(impl, new CompletableFuturePromiseFactory())
                    break
                case PromiseImplementation.DATAFLOW:
                    registerFactory(impl, new DataflowPromiseFactory(new DataflowFactory()))
                    break
                default:
                    log.error "could not find promise implementation for $impl in registry  "
            }
        }
    }

    /**
     * Get the default implementation type
     */
    static PromiseImplementation getDefaultImplementation() {
        return defaultImplementation
    }

    /**
     * Register/replace a factory at runtime.
     *
     * @param impl implementation key
     * @param factory factory instance
     */
    static void registerFactory(PromiseImplementation impl, PromiseFactory factory) {
        log.info "Registering factory for implementation: $impl"
        factories.putIfAbsent(impl, factory)
    }

    /**
     * Get a specific factory by implementation key.
     *
     * @param impl implementation type
     * @return the matching factory
     */
    static PromiseFactory getFactory(PromiseImplementation impl = null) {
        PromiseImplementation target = impl ?: defaultImplementation
        PromiseFactory factory = factories.get(target)

        if (!factory) {
            throw new IllegalStateException("No factory registered for implementation: $target")
        }

        return factory
    }

    /**
     * Get the Vertx instance (creates one if needed)
     */
    static Vertx getVertxInstance() {
        if (!vertxInstance) {
            vertxInstance = Vertx.vertx()
        }
        return vertxInstance
    }

    /**
     * Shutdown resources
     */
    static void shutdown() {
        if (vertxInstance) {
            vertxInstance.close()
            vertxInstance = null
        }
    }
}