package org.softwood.promise.core

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.vertx.core.Vertx

import org.softwood.promise.PromiseImplementation
import org.softwood.promise.PromiseFactory
import org.softwood.promise.core.cfuture.CompletableFuturePromiseFactory
import org.softwood.promise.core.dataflow.DataflowPromiseFactory
import org.softwood.promise.core.vertx.VertxPromiseFactory
import org.softwood.config.ConfigLoader
import org.softwood.dataflow.DataflowFactory

import java.util.EnumMap
import java.util.Map

/**
 * Central configuration, bootstrap, and factory registry for the Promises library.
 *
 * <h2>Overview</h2>
 * <p>
 * The {@code PromiseConfiguration} class is responsible for:
 * </p>
 * <ul>
 *     <li>Determining the default async implementation (DATAFLOW, VERTX, COMPLETABLE_FUTURE).</li>
 *     <li>Registering and exposing all available {@link PromiseFactory} implementations.</li>
 *     <li>Lazy-loading and instantiating the Vert.x runtime only when requested.</li>
 *     <li>Loading configuration from {@link ConfigLoader} (config.yml/json/groovy/properties).</li>
 *     <li>Supporting an override from configuration / environment / system properties.</li>
 * </ul>
 *
 * <h2>Configuration Sources</h2>
 * <p>
 * The default promise implementation may be set via:
 * </p>
 * <ul>
 *     <li>{@code promises.defaultImplementation} in any config file loaded by {@link ConfigLoader}</li>
 *     <li>Environment variables that ConfigLoader maps into this key</li>
 *     <li>System properties mapped into this key</li>
 *     <li>Or defaults to {@code DATAFLOW} if nothing else is provided</li>
 * </ul>
 *
 * <h2>Lazy VERTX Initialization</h2>
 * <p>
 * Vert.x cannot be constructed during class-load time because it requires a valid event-loop,
 * and scripts running Promises without Vert.x must not pay that cost or trigger failures.
 * For this reason, the Vert.x factory is registered as a {@code null} placeholder and created
 * lazily on first use.
 * </p>
 *
 * <h2>Error Prevention</h2>
 * <p>
 * This design prevents the original issue where {@code new VertxPromiseFactory(null)} caused
 * an {@link ExceptionInInitializerError}. With the lazy strategy, Vert.x is instantiated only
 * when specifically requested via:
 * </p>
 * <pre>
 *     Promises.newPromise(PromiseImplementation.VERTX)
 * </pre>
 */
@Slf4j
@CompileStatic
class PromiseConfiguration {

    /**
     * Registry mapping each {@link PromiseImplementation} to a {@link PromiseFactory}.
     * <p>
     * The map may contain {@code null} entries, particularly for VERTX, which is intentionally
     * lazily initialized.
     * </p>
     */
    private static final Map<PromiseImplementation, PromiseFactory> factories =
            new EnumMap<>(PromiseImplementation)

    /**
     * The default promise implementation to be used when callers do not choose one explicitly.
     * <p>
     * This value is resolved during static initialization based on application configuration.
     * </p>
     */
    private static PromiseImplementation defaultImplementation = PromiseImplementation.DATAFLOW

    /**
     * Lazily created Vert.x instance used by {@link VertxPromiseFactory}.
     * <p>
     * The instance remains {@code null} until a Vert.x-based Promise is requested.
     * </p>
     */
    private static Vertx vertxInstance = null

    // =========================================================================
    // Static Initialization
    // =========================================================================

    /**
     * Initializes the configuration system:
     * <ol>
     *     <li>Loads configuration via {@link ConfigLoader}</li>
     *     <li>Determines default implementation</li>
     *     <li>Registers built-in factories (lazy Vert.x)</li>
     * </ol>
     *
     * @throws Throwable if initialization fails
     */
    static {
        try {
            loadConfiguration()
            registerDefaultFactories()
        } catch (Throwable t) {
            log.error("Failed to initialize PromiseConfiguration: ${t.message}", t)
            throw t
        }
    }


    // =========================================================================
    // Configuration Loader
    // =========================================================================

    /**
     * Loads the promise configuration from {@link ConfigLoader}.
     * <p>
     * Reads the key {@code promises.defaultImplementation} and attempts to resolve it to
     * a {@link PromiseImplementation} enum constant. Unknown values fall back to DATAFLOW.
     * </p>
     */
    private static void loadConfiguration() {
        Map cfg = ConfigLoader.loadConfig()

        String raw = cfg.get("promises.defaultImplementation") as String
        if (raw) {
            raw = raw.trim()
            try {
                defaultImplementation = PromiseImplementation.valueOf(raw.toUpperCase())
                log.info("PromiseConfiguration: default implementation set to ${defaultImplementation}")
            }
            catch (IllegalArgumentException e) {
                log.warn("Invalid promises.defaultImplementation '${raw}', falling back to DATAFLOW")
                defaultImplementation = PromiseImplementation.DATAFLOW
            }
        }
        else {
            log.info("PromiseConfiguration: using fallback default implementation = DATAFLOW")
        }
    }


    // =========================================================================
    // Factory Registration
    // =========================================================================

    /**
     * Registers built-in implementations:
     * <ul>
     *     <li>{@link PromiseImplementation#DATAFLOW}</li>
     *     <li>{@link PromiseImplementation#COMPLETABLE_FUTURE}</li>
     *     <li>{@link PromiseImplementation#VERTX} (lazy-loaded)</li>
     * </ul>
     *
     * <p>
     * Vert.x factory is deliberately stored as {@code null} so that the runtime may initialize
     * it safely on first demand.
     * </p>
     */
    private static void registerDefaultFactories() {

        factories.put(
                PromiseImplementation.DATAFLOW,
                new DataflowPromiseFactory(new DataflowFactory())
        )

        factories.put(
                PromiseImplementation.COMPLETABLE_FUTURE,
                new CompletableFuturePromiseFactory()
        )

        // Lazy VERTX initialization (factory will be constructed on demand)
        factories.put(
                PromiseImplementation.VERTX,
                null
        )

        log.debug("PromiseConfiguration: registered DATAFLOW, COMPLETABLE_FUTURE, and lazy VERTX")
    }


    // =========================================================================
    // Factory Resolution
    // =========================================================================

    /**
     * Returns the {@link PromiseFactory} for the specified or default implementation.
     *
     * <p>
     * VERTX is created lazily — its factory is constructed only when requested.
     * </p>
     *
     * @param impl the desired implementation, or {@code null} to use the default
     * @return the resolved {@link PromiseFactory}
     * @throws IllegalStateException if no factory exists for the implementation
     */
    static PromiseFactory getFactory(PromiseImplementation impl = null) {

        PromiseImplementation target = impl ?: defaultImplementation
        PromiseFactory factory = factories.get(target)

        // Already loaded (DATAFLOW or COMPLETABLE_FUTURE or previously initialized VERTX)
        if (factory != null) {
            return factory
        }

        // Handle LAZY VERTX
        switch (target) {

            case PromiseImplementation.VERTX:
                if (vertxInstance == null) {
                    vertxInstance = Vertx.vertx()
                    log.info("PromiseConfiguration: created lazy Vertx instance")
                }

                factory = new VertxPromiseFactory(vertxInstance)
                factories.put(PromiseImplementation.VERTX, factory)

                log.info("PromiseConfiguration: VERTX factory initialized lazily")
                break

            default:
                throw new IllegalStateException(
                        "No promise factory registered for implementation: $target"
                )
        }

        return factory
    }


    // =========================================================================
    // Default Implementation Accessors
    // =========================================================================

    /**
     * @return the default promise implementation currently in use
     */
    static PromiseImplementation getDefaultImplementation() {
        return defaultImplementation
    }

    /**
     * Manually override the default promise implementation.
     *
     * @param impl the new default
     */
    static void setDefaultImplementation(PromiseImplementation impl) {
        defaultImplementation = impl
    }
}
