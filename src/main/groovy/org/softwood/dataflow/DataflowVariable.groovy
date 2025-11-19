package org.softwood.dataflow

import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowExpression

import java.util.concurrent.Callable
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Function

@Slf4j
class DataflowVariable<T> extends  DataflowExpression {
    DataflowVariable() {
        super ()
    }

    //initalise at declaration
    DataflowVariable(value) {
        super (value)
    }

    void set(T newValue) {
        setValue (newValue)
        log.debug "df now set to ${getValue()}"
    }

    void setVal (T newValue) {
        set (newValue)
    }

    T getValNonBlocking (T valueIfAbsent=null) {
        getValueOrElse {null}
    }

    /**
     * blocking get for the value - if already has error it just throws it
     * calls parent abstract DFE  getValue()
     * and in case that errors, throws the second error
     *
     * @return
     */
    T get () throws Exception {
        try {
            // First check if we have an error and throw it directly
            if (hasError()) {
                throw error
            }

            T result = (T) getValue()

            // Second check for error, in case it happened during getValue()
            if (hasError()) {
                throw error
            }
            return result
        } catch (Throwable ex) {
            // Set the error and rethrow it
            if (error == null) {
                setError(ex)
            }
            throw ex
        }
    }

    T getVal () {
        get()
    }

    Throwable getError () {
        super.error
    }

    boolean hasError () {
        isBound() && super.error != null
    }

    boolean isDone() {
        isBound()
    }

    /**
     * will throw timeout exception
     * @param timeout
     * @param unit
     * @return
     */
    T get(long timeout, TimeUnit unit) {
        try {
            return (T) getValue (timeout, unit)
        } catch (Exception ex) {
            setError(ex)
        }

    }

    T getVal (long timeout, TimeUnit unit) {
        get (timeout, unit)
    }

    /**
     * opertaor overload for left shift <<
     * @param value
     * @return
     */
    DataflowVariable leftShift (value) {
        setValue (value)
        this
    }

    Optional<T> getIfAvailable() {
        return isBound() ? Optional.ofNullable(get()) : Optional.empty()
    }


    /**
     * support df >> {it ->callable}, that will be called when df DFE value is set
     *
     * returns this so you call set this up multiple times with same bound value
     *
     * @param callable
     * @return this
     */
    DataflowVariable rightShift (callable) {
        DataflowVariable df = new DataflowVariable()

        log.debug "rightShift: call whenAvailable with the callable"
        if (isBound()) {
            def boundVal = get()
            callable.call (boundVal)
        } else {
            log.debug "adding callback to whenBound"
            whenBound("", callable)
        }
        this
    }

    void whenAvailable(Consumer<T> callback) {
       // Create a closure that calls the consumer
       Closure callbackClosure = { value ->
           log.debug "whenAvailable(): callback closure with value ($value), call consumer.accept"
           callback.accept(value) }

       // If already bound, execute immediately
       if (isBound()) {
           try {
               T boundValue = get()
               callback.accept(boundValue)
           } catch (Exception ex) {
               log.error "whenAvailable immediate execution of consumer failed: ${ex.message}"
           }
       } else {
           // Otherwise delegate to parent's whenBound for later notification
           log.debug "adding callback to whenBound"
           whenBound("", callbackClosure)
       }
    }


    void whenAvailable(Closure callbackClosure) {
        // If already bound, execute immediately
        if (isBound()) {
            try {
                T boundValue = get()
                callbackClosure.call(boundValue)
            } catch (Exception ex) {
                log.error "whenAvailable immediate execution of closure failed: ${ex.message}"
            }
        } else {
            // Otherwise delegate to parent's whenBound for later notification
            log.debug "adding callback closure to whenBound"
            whenBound("newValue", callbackClosure)
        }
    }

    DataflowVariable then(final callable ) {
        Closure closureCallable
        // Call the parent then() method which now returns a DataFlowVariable
        // Convert Function to Closure if needed
        if (callable instanceof Function) {
            closureCallable = { result -> callable.apply(result) }
        } else if (callable instanceof Consumer) {
            closureCallable = { result ->
                callable.accept(result)
                return result  // Return original value for Consumer
            }
        } else if (callable instanceof Callable) {
            closureCallable = { result -> callable.call() }
        } else {
            // Assume it's already a Closure
            closureCallable = callable
        }

        (DataflowVariable) super.then(callable)
    }

    /**
     * Creates a new DataFlowVariable that will attempt to recover from
     * errors in this variable by applying the recovery function
     * @param recovery A function that takes an error and produces a recovery value
     * @return A new DataFlowVariable containing the recovered value if an error occurs
     */
    <R> DataflowVariable<R> recover(Function<Throwable, R> recovery) {
        DataflowVariable<R> recoveredDataflow = new DataflowVariable<>()

        def internalFuture = this.toFuture()

        internalFuture.thenAccept(result -> {
            // If value is available normally, just pass it through
            // (with appropriate casting if needed)
            try {
                @SuppressWarnings("unchecked")
                R castValue = (R) value
                result.set(castValue)
            } catch (ClassCastException e) {
                result.setError(new IllegalStateException("Type mismatch during recovery", e))
            }
        }).exceptionally(throwable -> {
            // If there was an error, try to recover
            try {
                R recoveredValue = recovery.apply(throwable)
                recoveredDataflow.set(recoveredValue)
            } catch (Throwable e) {
                // If recovery itself fails, propagate that error
                recoveredDataflow.setError(new CompletionException(
                        "Error during recovery", e))
            }
            return null
        })

        return recoveredDataflow
    }

    /**
     * Dynamic method handling for operators
     * @param name Method name (e.g., 'plus', 'minus')
     * @param args Method arguments
     * @return A new DataFlowVariable with the result of the operation
     */
    def methodMissing(String name, args) {

        // List of supported operators
        def operators = [
                'plus': '+',
                'minus': '-',
                'multiply': '*',
                'div': '/',
                'power' : '**',
                'remainder' : '%'
        ]

        // Check if the method is an operator method
        if (operators.containsKey(name)) {
            DataflowVariable result = new DataflowVariable()

            // Get the first argument
            def arg = args[0]

            pool.executor.submit {
                try {
                    log.debug "methodMissing: trying operator $name getting values to assess  "
                    // Get this DataFlowVariable's value
                    def thisValue = this.get()

                    // Determine the value of the argument
                    def otherValue = (arg instanceof DataflowVariable)
                            ? arg.get()
                            : arg

                    // Use Groovy's invokeMethod to dynamically call the operator
                    def operationResult = thisValue."$name"(otherValue)

                    //set the value in the the DF result
                    log.debug "methodMissing: trying operation $name on ( $thisValue and $otherValue) setting result : $operationResult"
                    result.setValue(operationResult)
                    return operationResult
                } catch (Throwable ex) {
                    result.setError(ex)
                    throw ex
                }
            }

            return result
        }

        // If not an operator, throw a missing method exception
        throw new MissingMethodException(name, DataflowVariable.class, args)
    }

    /**
     * Provide a default implementation for common operators
     * This allows chaining and provides a fallback
     */
    def propertyMissing(String name) {
        // For operations like toInteger(), toString(), etc.
        this.get()."$name"()
    }

}