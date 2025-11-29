package org.softwood.promise.core

import groovy.util.logging.Slf4j
import org.softwood.config.ConfigLoader
import org.softwood.promise.PromiseFactory
import org.softwood.promise.PromiseImplementation
import org.softwood.promise.core.cfuture.CompletableFuturePromiseFactory
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import org.softwood.promise.core.vertx.VertxPromiseFactory
import org.softwood.dataflow.DataflowFactory
import io.vertx.core.Vertx

import java.util.concurrent.ConcurrentHashMap

/**
 * Central configuration point for resolving and selecting the default
 * {@link PromiseImplementation} used by the Promise subsystem.
 *
 * <h2>Overview</h2>
 * <p>
 * This class determines the default promise implementation at application startup.
 * It reads configuration via {@link ConfigLoader}, looking for the key:
 * </p>
 *
 * <pre>
 *     promises.defaultImplementation
 * </pre>
 *
 * <p>
 * The value should be a string matching one of the {@link PromiseImplementation} enum
 * constants (e.g. <code>DATAFLOW</code>, <code>VERTX</code>, <code>COMPLETABLE_FUTURE</code>).
 * Leading/trailing whitespace is trimmed and the lookup is case-insensitive.
 * </p>
 *
 * <h2>Resolution Rules</h2>
 * <ol>
 *   <li>If the configuration key exists, it is trimmed and resolved to an enum value.</li>
 *   <li>If the value is invalid or unresolvable, a warning is logged and the system
 *       falls back to {@link PromiseImplementation#DATAFLOW}.</li>
 *   <li>If the configuration key is absent entirely, DATAFLOW is used as the fallback.</li>
 * </ol>
 *
 * <h2>Factory Registration</h2>
 * <p>
 * During static initialization, factories for all known implementations are registered.
 * The resolved default implementation may be selected immediately, but factories for
 * all implementations remain available and can be swapped at runtime via
 * {@link #setDefaultImplementation}.
 * </p>
 *
 * <h2>Behavioral Guarantees</h2>
 * <ul>
 *     <li>Initialization occurs exactly once at class-load time.</li>
 *     <li>Enum resolution is safe and never throws; invalid values log a warning.</li>
 *     <li>The default implementation always has a corresponding registered factory.</li>
 *     <li>Factories are lazily created for VERTX.</li>
 * </ul>
 *
 * @author Will Woodman
 * @since 2025
 */
@Slf4j
class PromiseConfiguration {

    /** The resolved default implementation (falls back to DATAFLOW). */
    private static PromiseImplementation defaultImplementation = PromiseImplementation.DATAFLOW

    /** Registry of factories keyed by implementation type. */
    private static final Map<PromiseImplementation, PromiseFactory> factories = new ConcurrentHashMap<>()

    /** Lazily created Vertx instance used by VertxPromiseFactory. */
    private static Vertx vertxInstance

    /**
     * Static initialization block.
     *
     * <h3>Responsibilities</h3>
     * <ul>
     *     <li>Load merged configuration via {@link ConfigLoader}.</li>
     *     <li>Attempt to resolve <code>promises.defaultImplementation</code>.</li>
     *     <li>Trim, normalize, and safely map string to enum.</li>
     *     <li>Fall through to DATAFLOW if missing or invalid.</li>
     *     <li>Register all known factories (DATAFLOW, COMPLETABLE_FUTURE, VERTX).</li>
     * </ul>
     *
     * <h3>Example Config</h3>
     * <pre>
     * # config.yml
     * promises:
     *   defaultImplementation: VERTX
     * </pre>
     *
     * <p>
     * Note: The string lookup is case-insensitive and tolerant of surrounding whitespace.
     * </p>
     */
    static {

        // 1. Load configuration from all supported sources.
        Map config = ConfigLoader.loadConfig()

        // 2. Extract user-specified default implementation string.
        String rawValue = config.get("promises.defaultImplementation")

        if (rawValue) {
            String trimmed = rawValue.toString().trim()
            PromiseImplementation resolved = resolveEnum(trimmed)

            if (resolved) {
                log.info "Resolved default promise implementation from configuration: $resolved"
                defaultImplementation = resolved
            } else {
                log.warn "Invalid promises.defaultImplementation '${trimmed}', falling back to DATAFLOW"
                defaultImplementation = PromiseImplementation.DATAFLOW
            }

        } else {
            log.info "No promises.defaultImplementation found – using DATAFLOW"
            defaultImplementation = PromiseImplementation.DATAFLOW
        }

        // 3. Register the standard factories.
        registerFactory(PromiseImplementation.DATAFLOW,
                new DataflowPromiseFactory(new DataflowFactory()))

        registerFactory(PromiseImplementation.COMPLETABLE_FUTURE,
                new CompletableFuturePromiseFactory())

        registerFactory(PromiseImplementation.VERTX,
                new VertxPromiseFactory(vertxInstance))
    }

    /**
     * Safely convert a string to a {@link PromiseImplementation}, ignoring case.
     *
     * @param name the raw string from configuration
     * @return matching enum or null if invalid
     */
    private static PromiseImplementation resolveEnum(String name) {
        try {
            return PromiseImplementation.valueOf(name.toUpperCase())
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * Set the default implementation programmatically.
     *
     * <p>
     * If the implementation is not currently registered, its corresponding
     * factory is created lazily and inserted into the registry.
     * </p>
     *
     * @param impl the implementation to make default
     */
    static void setDefaultImplementation(PromiseImplementation impl) {
        log.info "Setting default promise implementation to: $impl"
        defaultImplementation = impl

        // Ensure the correct factory exists.
        if (!factories.containsKey(impl)) {
            switch (impl) {
                case PromiseImplementation.VERTX:
                    if (!vertxInstance) vertxInstance = Vertx.vertx()
                    registerFactory(impl, new VertxPromiseFactory(vertxInstance))
                    break

                case PromiseImplementation.COMPLETABLE_FUTURE:
                    registerFactory(impl, new CompletableFuturePromiseFactory())
                    break

                case PromiseImplementation.DATAFLOW:
                    registerFactory(impl, new DataflowPromiseFactory(new DataflowFactory()))
                    break
            }
        }
    }

    /**
     * Returns the active default implementation.
     *
     * @return the resolved {@link PromiseImplementation}
     */
    static PromiseImplementation getDefaultImplementation() {
        return defaultImplementation
    }

    /**
     * Register a factory for a given implementation.
     *
     * <p>
     * Factories are stored only if no entry exists for that key,
     * allowing late registration without overwriting earlier entries.
     * </p>
     *
     * @param impl    implementation family key
     * @param factory the new factory to register
     */
    static void registerFactory(PromiseImplementation impl, PromiseFactory factory) {
        log.info "Registering factory for implementation: $impl"
        factories.putIfAbsent(impl, factory)
    }

    /**
     * Retrieve the factory for the specified implementation.
     *
     * @param impl optional, defaults to current default implementation
     * @return the associated {@link PromiseFactory}
     * @throws IllegalStateException if no factory has been registered
     */
    static PromiseFactory getFactory(PromiseImplementation impl = null) {
        PromiseImplementation target = impl ?: defaultImplementation
        PromiseFactory factory = factories.get(target)

        if (!factory)
            throw new IllegalStateException("No factory registered for implementation: $target")

        return factory
    }

    /**
     * Obtain the shared Vertx instance, creating one if needed.
     *
     * @return a lazily initialized {@link Vertx} instance
     */
    static Vertx getVertxInstance() {
        if (!vertxInstance) {
            vertxInstance = Vertx.vertx()
        }
        return vertxInstance
    }

    /**
     * Shutdown hook for releasing Vert.x resources.
     *
     * <p>Calling this will close the Vert.x instance and clear its reference.</p>
     */
    static void shutdown() {
        if (vertxInstance) {
            vertxInstance.close()
            vertxInstance = null
        }
    }
}
