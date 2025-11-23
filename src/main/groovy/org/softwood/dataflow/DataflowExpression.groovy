package org.softwood.dataflow

import groovy.beans.Bindable
import groovy.transform.MapConstructor
import groovy.util.logging.Slf4j
import io.netty.util.concurrent.CompleteFuture
import org.softwood.pool.ConcurrentPool
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Abstract base class implementing dataflow expression semantics for concurrent programming.
 *
 * <p>A DataflowExpression represents a single-assignment variable that can be set once and read many times.
 * It provides thread-safe, non-blocking access to values that may not yet be available, enabling
 * declarative concurrent programming without explicit locks or synchronization.</p>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li><b>Single Assignment:</b> Can only be set once, subsequent attempts throw DataflowException</li>
 *   <li><b>Blocking Reads:</b> Reading an unbound variable blocks until a value is available</li>
 *   <li><b>Listener Support:</b> Register callbacks via whenBound() to be notified when value is set</li>
 *   <li><b>Error Handling:</b> Supports propagating exceptions to all waiting readers</li>
 *   <li><b>PropertyChangeSupport:</b> Fires property change events when value is bound</li>
 *   <li><b>Future Conversion:</b> Can be converted to CompletableFuture for integration with Java async APIs</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * // Create and bind a dataflow variable
 * def dfv = new DataflowVariable()
 * dfv.whenBound("Result available") { value, msg ->
 *     println "Received: $value with message: $msg"
 * }
 * dfv.setValue(42)  // Triggers the whenBound callback
 *
 * // Chain transformations
 * def result = dfv.then { it * 2 }  // Creates new DFV with transformed value
 * println result.getValue()  // Prints: 84
 *
 * // Handle errors
 * def errorDfv = new DataflowVariable()
 * errorDfv.setError(new RuntimeException("Failed"))
 * try {
 *     errorDfv.getValue()
 * } catch (RuntimeException e) {
 *     println "Caught: ${e.message}"
 * }
 * </pre>
 *
 * <h3>Thread Safety:</h3>
 * <p>All operations are thread-safe. Multiple threads can safely read, set, or register listeners
 * on the same DataflowExpression instance. The first thread to call setValue() wins, and all
 * other attempts will throw DataflowException.</p>
 *
 * @param <T> the type of value held by this dataflow expression
 * @author William Woodman
 * @see DataflowVariable
 * @see CompletableFuture
 */
@Slf4j
abstract class DataflowExpression<T> {

    //add pcs support via @Bindable
    @Bindable CompletableFuture<T> value = new CompletableFuture<T>()
    private final ReentrantLock setValueLock = new ReentrantLock()

    protected volatile Throwable error
    protected AtomicReference<DataflowState> state = new AtomicReference (DataflowState.NOT_INITIALISED)

    protected ConcurrentPool pool = new ConcurrentPool()
    volatile LocalDateTime timestamp

    ConcurrentLinkedQueue listeners = new ConcurrentLinkedQueue<MessageClosure>()

    /**
     * Represents the lifecycle states of a DataflowExpression.
     */
    static enum DataflowState {
        /** Initial state before any value assignment */
        NOT_INITIALISED,
        /** Transitional state during value resolution and assignment */
        INITIALISING,
        /** Final state after value has been successfully set or an error occurred */
        INITIALIZED
    }

    /**
     * Creates an unbound DataflowExpression. Call setValue() to bind it.
     */
    DataflowExpression() {
    }

    /**
     * Creates a DataflowExpression and immediately binds it with the provided data.
     *
     * @param data the value to bind, can be a direct value, Closure, or Callable
     */
    DataflowExpression(data) {
        setValue (data)
    }


    /**
     * Checks if this expression has been bound to a value.
     *
     * @return true if setValue() has completed successfully or setError() has been called
     */
    boolean isBound () { value.isDone()}

    /**
     * Alias for isBound(). Checks if this expression has been set.
     *
     * @return true if a value or error has been set
     */
    boolean isSet() { value.isDone()}

    /**
     * Sets an error state for this expression, completing it exceptionally.
     * All waiting readers will receive this exception when they call getValue().
     *
     * @param err the error to propagate to readers
     */
    void setError (Throwable err) {
        error = err
        // If the value isn't already completed, complete it exceptionally
        if (!value.isDone()) {
            value.completeExceptionally(err)
        }
        // Also set the state to initialized to indicate we're done
        state.set(DataflowState.INITIALIZED)
    }

    /**
     * Checks if this expression completed with an error.
     *
     * @return true if setError() was called or setValue() failed
     */
    boolean hasError () {
        error != null
    }

    /**
     * Retrieves the value, blocking until it becomes available.
     *
     * <p>This is a blocking call that will wait indefinitely until either:
     * <ul>
     *   <li>A value is set via setValue()</li>
     *   <li>An error is set via setError()</li>
     * </ul>
     *
     * @return the bound value of type T
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws Throwable if the expression was completed exceptionally via setError()
     */
    T getValue () throws InterruptedException {
        try {
            return value.get()
        } catch (ExecutionException ex) {
            // If the cause is set, grab it, otherwise use the exception itself
            Throwable cause = ex.getCause() ?: ex
            error = cause
            throw cause
        }
    }

    /**
     * Retrieves the value if bound, otherwise returns the result of the supplied function.
     *
     * @param orElseResult a Supplier providing the alternative value if not bound
     * @return the bound value or the result of orElseResult
     * @throws InterruptedException if interrupted while retrieving the value
     */
    T getValueOrElse (Supplier<T> orElseResult ) throws InterruptedException {
        isBound() ? value.get() : orElseResult.get()
    }

    /**
     * Retrieves the value with a timeout, blocking until available or timeout expires.
     *
     * @param waitTime the maximum time to wait
     * @param unit the time unit of the waitTime argument
     * @return the bound value of type T
     * @throws TimeoutException if the wait times out before value is available
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws Throwable if the expression was completed exceptionally
     */
    T getValue (long waitTime, TimeUnit unit) throws TimeoutException{
        try {
            value.get(waitTime, unit)
        } catch (ExecutionException ex) {
            // If the cause is set, grab it, otherwise use the exception itself
            Throwable cause = ex.getCause() ?: ex
            error = cause
            throw cause
        }
    }

    /**
     * Waits for the value to be set and returns it, throwing any exception unwrapped.
     * Unlike getValue(), this does not wrap exceptions in ExecutionException.
     *
     * @return the bound value of type T
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws Throwable if the expression was completed exceptionally
     */
    T join () throws InterruptedException{
        value.join()
    }

    /**
     * Waits for the value with a timeout and returns it, throwing any exception unwrapped.
     *
     * @param waitTime the maximum time to wait
     * @param unit the time unit of the waitTime argument
     * @return the bound value of type T
     * @throws TimeoutException if the wait times out
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws Throwable if the expression was completed exceptionally
     */
    T join (long waitTime, TimeUnit unit)  throws TimeoutException {
        value.get (waitTime, unit )
    }

    /**
     * Returns the underlying CompletableFuture for integration with Java async APIs.
     *
     * @return the CompletableFuture backing this dataflow expression
     */
    CompletableFuture<T> toFuture() {
        value
    }

    /**
     * Non-blocking check that returns this expression if bound, null otherwise.
     * Useful for polling without blocking.
     *
     * @return this DataflowExpression if bound, null if still waiting for a value
     */
    DataflowExpression<T> poll() {
        isBound() ? this : null
    }

    /**
     * Registers a callback to be invoked when this expression is bound, using a custom thread pool.
     *
     * @param pool the ConcurrentPool to use for executing the callback
     * @param message a descriptive message passed to the callback
     * @param closure the callback to invoke, receives (value, message) as parameters
     */
    void whenBound (ConcurrentPool pool, String message, final Closure closure  ) {
        this.pool = pool
        whenBound (message, closure )
    }

    /**
     * Registers a Consumer callback to be invoked when this expression is bound, using a custom thread pool.
     *
     * @param pool the ConcurrentPool to use for executing the callback
     * @param message a descriptive message passed to the callback
     * @param consumer the Consumer callback to invoke
     */
    void whenBound (ConcurrentPool pool, String message, final Consumer consumer  ) {
        whenBound( pool, message, consumer as Closure)
    }

    /**
     * Registers a callback to be invoked when this expression is bound.
     *
     * <p>If the expression is already bound when this method is called, the callback
     * executes immediately on the calling thread. Otherwise, it's queued and will be
     * invoked asynchronously when setValue() is called.</p>
     *
     * <p>The closure can accept 0, 1, or 2 parameters:
     * <ul>
     *   <li>0 params: { -> println "Bound!" }</li>
     *   <li>1 param: { value -> println "Value: $value" }</li>
     *   <li>2 params: { value, msg -> println "$msg: $value" }</li>
     * </ul>
     *
     * @param message a descriptive message passed to the callback
     * @param closure the callback closure to invoke when bound
     */
    void whenBound (String message, final Closure closure  ) {
        Closure whenBoundClosure = closure.clone() as Closure
        whenBoundClosure.delegate = this
        def messageClosure = new MessageClosure (id: UUID.randomUUID(), messageText: message, closure:whenBoundClosure)

        // If already bound, execute immediately
        if (isBound()) {
            try {
                // Get the value and pass it to the closure
                T boundValue = getValue()
                messageClosure.send(boundValue, message)
            } catch (Exception ex) {
                log.error "whenBound immediate execution failed: ${ex.message}"
            }
        } else {
            // Otherwise add to listeners for later notification
            listeners.add(messageClosure)
        }
    }

    /**
     * Registers a Consumer callback to be invoked when this expression is bound.
     *
     * @param message a descriptive message passed to the callback
     * @param consumer the Consumer callback to invoke when bound
     */
    void whenBound (String message, final Consumer consumer  ) {
        whenBound( message, consumer as Closure)
    }

    /**
     * Creates a new DataflowExpression by applying a transformation to this expression's value.
     * This enables functional composition and chaining of dataflow operations.
     *
     * <p>The transformation is applied asynchronously when this expression is bound,
     * and the result is stored in a new DataflowVariable that is returned immediately.</p>
     *
     * <h3>Example:</h3>
     * <pre>
     * def dfv = new DataflowVariable()
     * def doubled = dfv.then { it * 2 }
     * def formatted = doubled.then { "Result: $it" }
     *
     * dfv.setValue(21)
     * println formatted.getValue()  // Prints: "Result: 42"
     * </pre>
     *
     * @param closure the transformation function to apply to the value
     * @return a new DataflowExpression containing the transformed value
     * @throws Exception if the transformation closure throws an exception
     */
    DataflowExpression<T> then (final Closure closure) throws Exception {
        log.debug "then: create new MessageClosure, and call value.thenApply where value status is $value"

        // Create a new DataFlowVariable for the result
        DataflowVariable<T> result = new DataflowVariable<>()

        value.thenApply { inputValue ->
            log.debug "then(): thenApply with inputValue set to: $inputValue"
            try {
                // Apply the transformation
                def transformedValue = closure.call(inputValue)
                // Set the result in the new variable
                result.set ((T) transformedValue)
                return transformedValue
            } catch (Exception ex) {
                result.setError(ex)
                throw ex
            }
        }

        //return new dataFlowVariable
        return result
    }

    /**
     * Converts this DataflowExpression to a CompletableFuture for integration with Java async APIs.
     *
     * @param target the CompletableFuture class (used for Groovy's asType coercion)
     * @return the underlying CompletableFuture
     */
    CompletableFuture<T> asType (CompletableFuture) {
        value
    }

    /**
     * Binds this expression to a value (single assignment semantics).
     *
     * <p>This method can only be called once per instance. Subsequent calls will throw
     * DataflowException. The value can be:
     * <ul>
     *   <li>A direct value: setValue(42)</li>
     *   <li>A Closure that computes the value: setValue({ 21 * 2 })</li>
     *   <li>A Callable: setValue(someCallable)</li>
     * </ul>
     *
     * <p>When called, this method:
     * <ol>
     *   <li>Resolves the value (calling Closure/Callable if needed)</li>
     *   <li>Completes the underlying CompletableFuture</li>
     *   <li>Records a timestamp</li>
     *   <li>Fires PropertyChangeEvent for "value" property</li>
     *   <li>Notifies all registered whenBound listeners</li>
     * </ol>
     *
     * @param val the value to bind (can be direct value, Closure, or Callable)
     * @param async if true (default), listeners are notified asynchronously in parallel;
     *              if false, listeners are notified synchronously (useful for testing)
     * @return this DataflowExpression for method chaining
     * @throws DataflowException if this expression has already been bound
     * @throws Throwable if value resolution (Closure/Callable execution) fails
     */
    DataflowExpression setValue (T val, async = true) {
        // Acquire the lock to ensure thread-safe state transition
        setValueLock.lock()

        T resolvedValue
        try {
            // Use compareAndSet for atomic state transition
            if (!state.compareAndSet(DataflowState.NOT_INITIALISED, DataflowState.INITIALISING)) {
                throw new DataflowException("DataflowVariable can only be set once ")
            }

            timestamp = LocalDateTime.now()

            try {
                //just set the value
                resolvedValue = resolveValue (val)

                // Complete the original future first (this is what clients are waiting on)
                value.complete(resolvedValue)

                log.debug "set DataFlow value to $resolvedValue "

            } catch (Throwable ex) {
                // Handle resolution errors
                error = ex
                value.completeExceptionally(ex)
                throw ex
            } finally {
                // Ensure state is always set to INITIALIZED
                state.set(DataflowState.INITIALIZED)
            }


            // Fire notifications synchronously to ensure test cases work
            log.debug "DataFlowExpression: notify clients, that value has been set to ($resolvedValue) at $timestamp"

            firePropertyChange("value", null, resolvedValue)
            async ? notifyWhenBoundAsync((T) resolvedValue) : notifyWhenBound((T) resolvedValue)  //will use executor to send

            this

        } finally {
            // Always release the lock
            setValueLock.unlock()
        }

    }

    /**
     * Safely resolves the input value which might be a Closure, Callable, or direct value.
     *
     * @param val the input value to resolve
     * @return the resolved value after calling Closure/Callable if needed
     * @throws Throwable if Closure/Callable execution fails
     */
    private T resolveValue(T val) {
        T resolvedValue
        try {
            if (val instanceof Closure) {
                resolvedValue = (T) val.call()
            } else if (val instanceof Callable) {
                resolvedValue = (Callable) val.call()
            } else {
                resolvedValue = val
            }
            return resolvedValue
        } catch (Throwable ex) {
            log.error "Error resolving closure value: ${ex.message}"
            throw ex
        }

    }

    /**
     * Synchronously notifies all registered whenBound listeners with the newly bound value.
     * Processes listeners sequentially on the calling thread.
     *
     * @param newValue the value that was just bound
     */
    protected void notifyWhenBound (T newValue) {
        assert timestamp
        log.debug "notifyWhenBound(): -send to all MessageClosure listeners with value: $newValue"

        // Make a copy of the listeners to avoid concurrent modification issues
        def listenersCopy = new ArrayList<>(listeners)
        listeners.clear() // Clear the original list since we're processing all listeners

        // Process each listener synchronously
        listenersCopy.each { MessageClosure mc ->
            try {
                //synchronous send
                mc.send(newValue, mc.messageText)
            } catch (Exception ex) {
                log.error "Error notifying $mc whenBound listener: ${ex.message}"
            }
        }
    }

    /**
     * Asynchronously notifies all registered whenBound listeners with the newly bound value.
     * Processes listeners in parallel using parallel streams for better performance.
     *
     * @param newValue the value that was just bound
     */
    protected void notifyWhenBoundAsync (T newValue) {
        assert timestamp
        log.debug "notifyWhenBoundAsync(): -send to all MessageClosure listeners with value: $newValue"

        // Make a copy of the listeners to avoid concurrent modification issues
        def listenersCopy = new ArrayList<>(listeners)
        listeners.clear() // Clear the original list since we're processing all listeners

        // Process each as parallel stream
        listenersCopy.parallelStream().each {MessageClosure mc ->
            try {
                mc.send (newValue, mc.messageText)
            } catch (Exception ex) {
                log.error "Error notifying $mc whenBound listener: ${ex.message}"
            }
        }
    }

    /**
     * Internal helper class representing a listener closure with associated metadata.
     *
     * <p>MessageClosure wraps a user-provided closure along with:
     * <ul>
     *   <li>A unique ID for tracking</li>
     *   <li>A message text for context</li>
     *   <li>The bound value when notification occurs</li>
     *   <li>A timestamp when the DataflowExpression was bound</li>
     * </ul>
     *
     * <p>Supports both synchronous (send) and asynchronous (sendAsync) execution modes.</p>
     *
     * @param <T> the type of value being communicated
     */
    @MapConstructor
    @Slf4j
    private class MessageClosure<T> {
        /** Unique identifier for this listener */
        String id
        /** Descriptive message text associated with this listener */
        String messageText
        /** The value that was bound (wrapped in Optional) */
        Optional<T> newValue = Optional.ofNullable(null)
        /** Timestamp when the parent DataflowExpression was bound */
        LocalDateTime whenDataFlowExpressionBoundDateTime = { -> timestamp }.call()

        @Delegate
        Closure closure
        /** Executor service for asynchronous execution */
        ExecutorService executor = new ConcurrentPool().executor

        /**
         * Creates a MessageClosure with an optional work closure.
         *
         * @param work the closure to execute when notified (delegates to this MessageClosure)
         */
        MessageClosure(@DelegatesTo(MessageClosure) Closure work = null) {
            id = UUID.randomUUID()
            closure = work ?: {}
        }

        /**
         * Sets the value to be passed to the closure when invoked.
         *
         * @param value the bound value from the DataflowExpression
         */
        void setNewValue(value) {
            log.debug "MessageClosure: setNewValue() called with $value"
            newValue = Optional.ofNullable(value)
        }

        /**
         * Asynchronously executes the closure in a thread pool.
         *
         * <p>The closure is invoked with 0, 1, or 2 parameters based on its signature:
         * <ul>
         *   <li>0 params: closure()</li>
         *   <li>1 param: closure(value)</li>
         *   <li>2 params: closure(value, message)</li>
         * </ul>
         *
         * @param message optional message to pass (defaults to messageText)
         * @return a Future representing the pending completion of the closure execution
         */
        Future sendAsync(String message = null) {
            whenDataFlowExpressionBoundDateTime = { -> timestamp }.call()
            log.debug "sendAsync(): message $message, at: $whenDataFlowExpressionBoundDateTime"
            def maxParams = closure.maximumNumberOfParameters
            Callable messageClosureCallPlan

            assert isBound()

            //based on closure params setup call to submit via the executor, async
            switch (maxParams) {
                case 0:
                    messageClosureCallPlan = { closure() } as Callable
                    break
                case 1:
                    messageClosureCallPlan = { closure(newValue.get()) } as Callable
                    break
                case 2:
                    //add the Optional newValue as first param
                    messageClosureCallPlan = { closure(newValue.get(), message ?: messageText) } as Callable
                    break
                default:
                    throw new IllegalArgumentException("expecting no more than 2 parameters for the mc.closure ")
            }

            Future future = executor.submit(messageClosureCallPlan)
            future
        }

        /**
         * Synchronously executes the closure with the provided value.
         *
         * @param value the value to pass to the closure
         * @param message optional message to pass (defaults to messageText)
         * @return the result of the closure execution
         */
        def send(value, String message = null) {
            setNewValue(value)
            send(message)
        }

        /**
         * Synchronously executes the closure on the calling thread.
         *
         * @param message optional message to pass (defaults to messageText)
         * @return the result of the closure execution
         */
        def send(String message = null) {
            whenDataFlowExpressionBoundDateTime = { -> timestamp }.call()
            log.debug "send(): message $message, at: $whenDataFlowExpressionBoundDateTime"
            def maxParams = closure.maximumNumberOfParameters

            assert isBound()

            //based on closure params notify synchronously
            def result
            switch (maxParams) {
                case 0:
                    result = closure()
                    break
                case 1:
                    result = closure(newValue.get())
                    break
                case 2:
                    //add the Optional newValue as first param
                    result = closure(newValue.get(), message ?: messageText)
                    break
                default:
                    throw new IllegalArgumentException("expecting no more than 2 parameters for the mc.closure ")
            }

            result
        }
    }
}