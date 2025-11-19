package org.softwood.dataflow

import groovy.beans.Bindable
import groovy.transform.MapConstructor
import groovy.util.logging.Slf4j

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

@Slf4j
abstract class DataflowExpression<T> {

    //add pcs support via @Bindable
     @Bindable CompletableFuture<T> value = new CompletableFuture<T>()
    private final ReentrantLock setValueLock = new ReentrantLock()

    protected volatile Throwable error
    protected AtomicReference<DataflowState> state = new AtomicReference (DataflowState.NOT_INITIALISED)

    protected ConcurrentPool pool = new ConcurrentPool()
    protected ExecutorService executor = pool.executor
    volatile LocalDateTime timestamp

    ConcurrentLinkedQueue listeners = new ConcurrentLinkedQueue<Closure>()

        static enum DataflowState {
        NOT_INITIALISED,
        INITIALISING,
        INITIALIZED
    }

    DataflowExpression() {
    }

    /**
     * allow DFE to bound upfront when declaring one
     * @param data
     */
    DataflowExpression(data) {
        setValue (data)
    }

    boolean isBound () { value.isDone()}

    boolean isSet() { value.isDone()}

    void setError (Throwable err) {
        error = err
        // If the value isn't already completed, complete it exceptionally
        if (!value.isDone()) {
            value.completeExceptionally(err)
        }
        // Also set the state to initialized to indicate we're done
        state.set(DataflowState.INITIALIZED)
    }

    boolean hasError () {
        error != null
    }

    //todo implement a get that either returns the value or waits /wait with timeout
    /**
     * blocking call get the value in the computeableFuture
     * @return instance value of type T
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

    T getValueOrElse (Supplier<T> orElseResult ) throws InterruptedException {
        isBound() ? value.get() : orElseResult.get()
    }

    /**
     * async get with timeout
     * @param waitTime
     * @param unit
     * @return instance value of type T
     * @throws InterruptedException
     */
    T getValue (int waitTime, TimeUnit unit) throws TimeoutException{
        try {
            value.get(waitTime, unit)
        } catch (ExecutionException ex) {
            // If the cause is set, grab it, otherwise use the exception itself
            Throwable cause = ex.getCause() ?: ex
            error = cause
            throw cause
        }
    }

    T join () throws InterruptedException{

        value.join()
    }

    T join (int waitTime, TimeUnit unit)  throws TimeoutException {
        value.get (waitTime, unit )
    }

    CompletableFuture<T> toFuture() {
        value
    }

    /**
     * retrieves bound DFV value or null
     *
     * @return DataFlowExpression if bound
     */
    DataflowExpression<T> poll() {
        isBound() ? this : null

    }

    /**
     * expected to be called prior to the DataFlowExpression having a value
     * @param pool
     * @param message
     * @param closure
     */
    void whenBound (ConcurrentPool pool, String message, final Closure closure  ) {
        this.pool = pool
        whenBound (message, closure )
    }

    /**
     *
     * @param pool
     * @param message
     * @param consumer<T> - functional interface that returns T
     */
    void whenBound (ConcurrentPool pool, String message, final Consumer consumer  ) {
        whenBound( pool, message, consumer as Closure)
    }

    /**
     * whenBound notifies an MessageClosure listeners when value is set
     * if bound already just directly calls the MessageClosure.send(), otherwise
     * adds to listeners in waiting
     * @param message
     * @param consumer
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
     * whenBound notifies an MessageClosure listeners when value is set
     * if bound already just directly calls the MessageClosure.send(), otherwise
     * adds to listeners in waiting
     * @param message
     * @param consumer Functional consumer to call
     */
    void whenBound (String message, final Consumer consumer  ) {
        whenBound( message, consumer as Closure)
    }

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
                result.set(transformedValue)
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
     * get the DF as CompletableFuture
     * @param CompletableFuture
     * @return
     */
    CompletableFuture<T> asType (CompletableFuture) {
        value
    }


    /**
     * sets the value for this DFV and updates any listeners and fires propertyChange Event
     * @param val
     * async - if true notifications will be asynchronous, if false (such as testing)
     * the notifications will be fired synchronously
     * @return this DataFlowExpression
     */
    DataflowExpression setValue (T val, async = true) {
        // Acquire the lock to ensure thread-safe state transition
        setValueLock.lock()

        T resolvedValue
        try {
            // Use compareAndSet for atomic state transition
            if (!state.compareAndSet(DataFlowState.NOT_INITIALISED, DataFlowState.INITIALISING)) {
                throw new DataflowException("DataFlowVariable can only be set once ")
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
                state.set(DataFlowState.INITIALIZED)
            }


            // Fire notifications synchronously to ensure test cases work
            log.debug "DataFlowExpression: notify clients, that value has been set to ($resolvedValue) at $timestamp"

            firePropertyChange("value", null, resolvedValue)
            async ? notifyWhenBoundAsync(resolvedValue) : notifyWhenBound(resolvedValue)  //will use executor to send

            this

        } finally {
            // Always release the lock
            setValueLock.unlock()
        }

    }

    /**
     * Safely resolve the input value which might be a cloure or just a variable
     *
     * @param val The input value to resolve
     * @return The resolved value
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
     * will iterate across any MethodClosure listeners and send each message when the DFV is bound
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
     * creates copy of listeners, and calls each entry in parallel using parallelStream
     * @param newValue from set action on the value concurrentFuture
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

    @MapConstructor
    @Slf4j
    private class MessageClosure<T> {
        String id
        String messageText
        Optional<T> newValue = Optional.ofNullable(null)
        LocalDateTime whenDataFlowExpressionBoundDateTime = { -> timestamp }.call()

        @Delegate
        Closure closure
        ExecutorService executor = new ConcurrentPool().executor

        //delegate is already set to dataFlowExpression
        MessageClosure(@DelegatesTo(MessageClosure) Closure work = null) {
            id = UUID.randomUUID()
            closure = work ?: {}
        }

        void setNewValue(value) {
            log.debug "MessageClosure: setNewValue() called with $value"
            newValue = Optional.ofNullable(value)
        }

        /**
         * when value is bound, MessageClosure runs its closure via the virtual thread and returns the future
         * @param message
         * @return
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
         * synchronous send action
         * @param value
         * @param message
         * @return
         */
        def send(value, String message = null) {
            setNewValue(value)
            send(message)
        }

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
