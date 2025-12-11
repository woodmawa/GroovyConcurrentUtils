package org.softwood.dataflow

import groovy.lang.GroovyObjectSupport
import groovy.util.logging.Slf4j
import org.codehaus.groovy.runtime.InvokerInvocationException

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Dynamic, thread-safe namespace of {@link DataflowVariable} instances.
 *
 * <p>A {@code Dataflows} instance acts as a dynamic property container where each
 * property name corresponds to a lazily-created {@link DataflowVariable}. Reading
 * a property (e.g. {@code df.x}) blocks until the variable is bound, while writing
 * a property (e.g. {@code df.x = 1}) binds it exactly once.</p>
 *
 * <h3>Features</h3>
 * <ul>
 *     <li>Dynamic property syntax: {@code df.a}, {@code df.b = 2}</li>
 *     <li>Array-style access: {@code df[0] = 99}, {@code df[0]}</li>
 *     <li>Single-assignment for each DataflowVariable</li>
 *     <li>Non-blocking snapshots of bound/unbound variables</li>
 *     <li>DFV-to-DFV wiring when setting with another {@link DataflowVariable}</li>
 *     <li>Callback DSL: {@code df.x { v -> ... }}</li>
 * </ul>
 */
@Slf4j
class Dataflows extends GroovyObjectSupport {

    /** Backing storage for propertyName → DataflowVariable. */
    private final ConcurrentMap<Object, DataflowVariable<?>> variables = new ConcurrentHashMap<>()

    /** Factory used to create new DataflowVariables for this namespace. */
    private final DataflowFactory factory

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /**
     * Creates an empty {@code Dataflows} container backed by a default {@link DataflowFactory}.
     */
    Dataflows() {
        this(new DataflowFactory())
    }

    /**
     * Creates an empty {@code Dataflows} container backed by the supplied {@link DataflowFactory}.
     *
     * @param factory factory used to create all underlying {@link DataflowVariable} instances
     */
    Dataflows(DataflowFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("DataflowFactory must not be null")
        }
        this.factory = factory
        log.debug "Dataflows instance created with factory $factory"
    }

    // ---------------------------------------------------------------------
    // Public DFV access helpers (used by tests & wiring)
    // ---------------------------------------------------------------------

    /**
     * Return the {@link DataflowVariable} for the given name, creating it if needed.
     * This method does <em>not</em> block.
     *
     * @param name DFV name
     * @return existing or newly-created DFV
     * @throws IllegalArgumentException if {@code name} is null
     */
    DataflowVariable<?> getDataflowVariable(Object name) {
        if (name == null)
            throw new IllegalArgumentException("Property name cannot be null")
        return ensureDFV(name)
    }

    /**
     * @param name DFV name
     * @return {@code true} if a DataflowVariable exists for this name, {@code false} otherwise
     */
    boolean contains(Object name) {
        return variables.containsKey(name)
    }

    // ---------------------------------------------------------------------
    // Array-style read/write (df[index])
    // ---------------------------------------------------------------------

    /**
     * Array-style and direct read: {@code df[index]}.
     * <p>Blocks until the corresponding DFV is bound.</p>
     *
     * @param index key/name
     * @return bound value
     */
    Object getAt(Object index) {
        if (index == null)
            throw new IllegalArgumentException("Index cannot be null")

        log.trace "getAt: $index (blocking)"
        try {
            return ensureDFV(index).getValue()
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new InvokerInvocationException(e)
        }
    }

    /**
     * Array-style write: {@code df[index] = value}.
     * <p>If {@code value} is a {@link DataflowVariable}, wires them. Otherwise binds the DFV to
     * the scalar value.</p>
     *
     * @param index key/name
     * @param value DFV or scalar to bind
     */
    void putAt(Object index, Object value) {
        if (index == null)
            throw new IllegalArgumentException("Index cannot be null")

        log.debug "putAt: index=$index, value=${value?.class?.simpleName}"
        bindToDFV(index, value)
    }

    // ---------------------------------------------------------------------
    // Dynamic propertyMissing for df.x and df.x = y
    // ---------------------------------------------------------------------

    /**
     * Dynamic read for unknown properties: {@code df.someVar}.
     * <p>Creates the DFV if required and blocks until it is bound.</p>
     *
     * @param name property name
     * @return bound value for {@code name}
     */
    Object propertyMissing(String name) {
        log.trace "propertyMissing(read): $name"
        try {
            return ensureDFV(name).getValue()
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new InvokerInvocationException(e)
        }
    }

    /**
     * Dynamic write for unknown properties: {@code df.someVar = value}.
     * <p>If {@code value} is a {@link DataflowVariable}, the two DFVs are wired so that when
     * the source is bound, the target will be bound to the same value.</p>
     *
     * @param name property name
     * @param value scalar value or DFV to bind
     * @return current (non-blocking) value of the DFV after binding
     */
    Object propertyMissing(String name, Object value) {
        log.trace "propertyMissing(write): $name"

        try {
            bindToDFV(name, value)
            return ensureDFV(name).getNonBlocking()
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Property '$name' already bound", e)
        }
    }

    // ---------------------------------------------------------------------
    // df.x { v -> ... } style callbacks
    // ---------------------------------------------------------------------

    /**
     * Method-style callback syntax: {@code df.x { v -> ... }}.
     * <p>This registers a when-bound callback on the DFV named {@code name}.</p>
     *
     * @param name DFV name
     * @param args must contain exactly one {@link Closure} parameter
     * @return {@code this} for fluent chaining
     * @throws MissingMethodException if arguments are not a single Closure
     */
    @Override
    Object invokeMethod(String name, Object args) {
        log.trace "invokeMethod: $name"

        if (args instanceof Object[]) {
            Object[] argv = (Object[]) args
            if (argv.length == 1 && argv[0] instanceof Closure) {
                Closure<?> closure = (Closure<?>) argv[0]
                getDataflowVariable(name).whenBound("Dataflows::invokeMethod", closure)
                return this
            }
        }

        throw new MissingMethodException(name, Dataflows, args as Object[])
    }

    // ---------------------------------------------------------------------
    // Internal DFV management
    // ---------------------------------------------------------------------

    /**
     * Ensures a {@link DataflowVariable} exists in the map for {@code key}.
     * This method never blocks.
     *
     * @param key DFV key
     * @return existing or newly-created DFV
     */
    private DataflowVariable<?> ensureDFV(Object key) {
        DataflowVariable<?> existing = variables.get(key)
        if (existing != null)
            return existing

        DataflowVariable<?> created = factory.createDataflowVariable()
        existing = variables.putIfAbsent(key, created)
        return existing != null ? existing : created
    }

    /**
     * Binds a scalar or wires one DFV into another.
     *
     * @param key DFV name
     * @param newValue DFV or scalar
     */
    private void bindToDFV(Object key, Object newValue) {
        DataflowVariable<?> target = ensureDFV(key)

        if (newValue instanceof DataflowVariable) {
            DataflowVariable<?> source = (DataflowVariable<?>) newValue
            source.whenBound("Dataflows::bindToDFV") { v ->
                try {
                    target.bind(v)
                } catch (DataflowException ignored) {
                    log.debug "DFV '$key' already bound during wiring completion"
                }
            }
        } else {
            target.bind(newValue)
        }
    }

    // ---------------------------------------------------------------------
    // Admin: remove, clear, size, isEmpty, keySet
    // ---------------------------------------------------------------------

    /**
     * Removes a DFV. If it is unbound, binds it to {@code null} first.
     *
     * @param name DFV name
     * @return the removed DFV, or {@code null} if none existed
     */
    DataflowVariable<?> remove(Object name) {
        if (name == null)
            throw new IllegalArgumentException("Name cannot be null")

        DataflowVariable<?> dfv = variables.remove(name)
        if (dfv != null && !dfv.isBound()) {
            try {
                dfv.bind(null)
            } catch (DataflowException ignored) {
                // may have been bound concurrently
            }
        }
        return dfv
    }

    /**
     * Removes all DFVs, binding any unbound variables to {@code null} first.
     */
    void clear() {
        variables.forEach { k, dfv ->
            if (!dfv.isBound()) {
                try {
                    dfv.bind(null)
                } catch (ignored) {
                    // ignore concurrent binding
                }
            }
        }
        variables.clear()
    }

    /**
     * @return number of DFVs currently stored
     */
    int size() {
        return variables.size()
    }

    /**
     * @return {@code true} if no DFVs are present
     */
    boolean isEmpty() {
        return variables.isEmpty()
    }

    /**
     * @return snapshot of all DFV names as a {@link Set}
     */
    Set<Object> keySet() {
        return new HashSet<>(variables.keySet())
    }

    // ---------------------------------------------------------------------
    // Non-blocking snapshots: bound values & property sets
    // ---------------------------------------------------------------------

    /**
     * Returns a non-blocking snapshot of all bound DFVs and their values.
     * <p>Unbound DFVs are skipped.</p>
     *
     * @return immutable map of DFV name → bound value
     */
    Map<Object, Object> getBoundValues() {
        Map<Object, Object> snapshot = new LinkedHashMap<>()
        variables.forEach { name, dfv ->
            if (dfv.isBound()) {
                snapshot[name] = dfv.getNonBlocking()
            }
        }
        return Collections.unmodifiableMap(snapshot)
    }

    /**
     * @return set of all DFV names that are currently bound
     */
    Set<Object> getBoundProperties() {
        Set<Object> result = new LinkedHashSet<>()
        variables.forEach { k, dfv ->
            if (dfv.isBound()) {
                result.add(k)
            }
        }
        return result
    }

    /**
     * @return set of all DFV names that are currently unbound
     */
    Set<Object> getUnboundProperties() {
        Set<Object> result = new LinkedHashSet<>()
        variables.forEach { k, dfv ->
            if (!dfv.isBound()) {
                result.add(k)
            }
        }
        return result
    }

    // ---------------------------------------------------------------------
    // Iterator support
    // ---------------------------------------------------------------------

    /**
     * Returns a snapshot iterator over DFV entries (name → DFV).
     *
     * @return iterator over map entries
     */
    Iterator<Map.Entry<Object, DataflowVariable<?>>> iterator() {
        return new ArrayList<>(variables.entrySet()).iterator()
    }
}