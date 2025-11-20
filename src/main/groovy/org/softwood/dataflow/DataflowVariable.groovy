package org.softwood.dataflow

import groovy.util.logging.Slf4j
import org.softwood.dataflow.DataflowExpression

import java.util.concurrent.Callable
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Function

/**
 * Concrete implementation of a dataflow variable with single-assignment semantics and operator overloading.
 *
 * <p>DataflowVariable extends {@link DataflowExpression} to provide a practical, user-friendly interface
 * for dataflow concurrent programming. It adds convenience methods, operator overloading, and enhanced
 * error handling while maintaining the core single-assignment guarantee.</p>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li><b>Simplified API:</b> Provides get()/set() methods as alternatives to getValue()/setValue()</li>
 *   <li><b>Operator Overloading:</b> Supports arithmetic operators (+, -, *, /, **, %) for dataflow composition</li>
 *   <li><b>Left Shift Assignment:</b> Use {@code dfv << value} as syntactic sugar for setting values</li>
 *   <li><b>Right Shift Callback:</b> Use {@code dfv >> closure} to register callbacks in a fluent style</li>
 *   <li><b>Error Recovery:</b> Provides recover() for handling and recovering from errors</li>
 *   <li><b>Non-blocking Access:</b> getValNonBlocking() and getIfAvailable() for polling</li>
 *   <li><b>Method Chaining:</b> Most operations return 'this' for fluent programming style</li>
 * </ul>
 *
 * <h3>Basic Usage:</h3>
 * <pre>
 * // Simple set and get
 * def dfv = new DataflowVariable()
 * dfv.set(42)
 * println dfv.get()  // Prints: 42
 *
 * // Operator overloading - left shift for assignment
 * def x = new DataflowVariable()
 * x << 10  // Equivalent to x.set(10)
 *
 * // Right shift for callbacks
 * def y = new DataflowVariable()
 * y >> { value -> println "Got: $value" }
 * y.set(20)  // Prints: "Got: 20"
 * </pre>
 *
 * <h3>Operator Overloading:</h3>
 * <pre>
 * def a = new DataflowVariable()
 * def b = new DataflowVariable()
 *
 * // Arithmetic operations create new DataflowVariables
 * def sum = a.plus(b)      // or: a + b (when operators are overloaded)
 * def product = a.multiply(b)  // or: a * b
 *
 * a.set(5)
 * b.set(3)
 *
 * println sum.get()      // Prints: 8
 * println product.get()  // Prints: 15
 * </pre>
 *
 * <h3>Advanced Features:</h3>
 * <pre>
 * // Error handling with recovery
 * def risky = new DataflowVariable()
 * def safe = risky.recover { error ->
 *     println "Error occurred: ${error.message}"
 *     return 0  // Default value
 * }
 * risky.setError(new RuntimeException("Failed"))
 * println safe.get()  // Prints: 0
 *
 * // Chaining transformations
 * def original = new DataflowVariable()
 * def doubled = original.then { it * 2 }
 * def formatted = doubled.then { "Result: $it" }
 *
 * original.set(21)
 * println formatted.get()  // Prints: "Result: 42"
 *
 * // Non-blocking access
 * def pending = new DataflowVariable()
 * def maybe = pending.getIfAvailable()
 * if (maybe.present) {
 *     println "Value: ${maybe.get()}"
 * } else {
 *     println "Not ready yet"
 * }
 * </pre>
 *
 * <h3>Fluent Programming Style:</h3>
 * <pre>
 * def dfv = new DataflowVariable()
 *     .whenAvailable { println "First callback: $it" }
 *
 * dfv >> { println "Second callback: $it" }
 * dfv >> { println "Third callback: $it" }
 *
 * dfv.set(100)  // All three callbacks execute
 * </pre>
 *
 * <h3>Thread Safety:</h3>
 * <p>All operations are thread-safe. Multiple threads can safely read, set, or register callbacks.
 * The first thread to call set() wins, subsequent calls throw DataflowException.</p>
 *
 * @param <T> the type of value held by this dataflow variable
 * @author William Woodman
 * @see DataflowExpression
 * @since 1.0
 */
@Slf4j
class DataflowVariable<T> extends  DataflowExpression {

    /**
     * Creates an unbound DataflowVariable. Call set() to bind it.
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     * // dfv is unbound, calling get() would block
     * dfv.set(42)
     * // Now dfv is bound with value 42
     * </pre>
     */
    DataflowVariable() {
        super ()
    }

    /**
     * Creates a DataflowVariable and immediately binds it with the provided value.
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable(100)
     * println dfv.get()  // Prints: 100 (no blocking)
     * </pre>
     *
     * @param value the initial value to bind (can be direct value, Closure, or Callable)
     */
    DataflowVariable(value) {
        super (value)
    }

    /**
     * Binds this variable to a value (single assignment semantics).
     * This is a convenience method that delegates to setValue() from the parent class.
     *
     * <p>This method can only be called once. Subsequent calls will throw DataflowException.
     * Use this method when you want clear, explicit set semantics rather than operator overloading.</p>
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     * dfv.set(42)
     * // dfv.set(100)  // Would throw DataflowException
     * </pre>
     *
     * @param newValue the value to bind
     * @throws DataflowException if this variable has already been bound
     */
    void set(T newValue) {
        setValue (newValue)
        log.debug "df now set to ${getValue()}"
    }

    /**
     * Alias for set(). Binds this variable to a value.
     * Provided for compatibility with different naming conventions.
     *
     * @param newValue the value to bind
     * @throws DataflowException if this variable has already been bound
     * @see #set(T)
     */
    void setVal (T newValue) {
        set (newValue)
    }

    /**
     * Non-blocking retrieval of the value. Returns immediately with the value if bound,
     * or the provided default value if not yet bound.
     *
     * <p>This is useful for polling scenarios where you don't want to block waiting
     * for a value. The default behavior returns null if unbound.</p>
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     * def val = dfv.getValNonBlocking(0)
     * println val  // Prints: 0 (default, since unbound)
     *
     * dfv.set(42)
     * val = dfv.getValNonBlocking(0)
     * println val  // Prints: 42
     * </pre>
     *
     * @param valueIfAbsent the value to return if this variable is not yet bound (defaults to null)
     * @return the bound value, or valueIfAbsent if not bound
     */
    T getValNonBlocking (T valueIfAbsent=null) {
        getValueOrElse {null}
    }

    /**
     * Retrieves the value, blocking until it becomes available.
     *
     * <p>This method provides enhanced error handling compared to the parent getValue():
     * <ul>
     *   <li>Checks for existing errors before blocking</li>
     *   <li>Checks for errors after retrieval</li>
     *   <li>Ensures error state is properly set if an exception occurs</li>
     * </ul>
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     *
     * Thread.start {
     *     Thread.sleep(1000)
     *     dfv.set(42)
     * }
     *
     * def result = dfv.get()  // Blocks for ~1 second
     * println result  // Prints: 42
     * </pre>
     *
     * @return the bound value of type T
     * @throws Exception if an error was set via setError() or occurred during value resolution
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

    /**
     * Alias for get(). Retrieves the value, blocking until available.
     * Provided for compatibility with different naming conventions.
     *
     * @return the bound value of type T
     * @throws Exception if an error occurred
     * @see #get()
     */
    T getVal () {
        get()
    }

    /**
     * Retrieves the error that was set via setError(), if any.
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     * dfv.setError(new RuntimeException("Something failed"))
     *
     * Throwable err = dfv.getError()
     * println err.message  // Prints: "Something failed"
     * </pre>
     *
     * @return the error Throwable, or null if no error has been set
     */
    Throwable getError () {
        super.error
    }

    /**
     * Checks if this variable has completed with an error.
     * Only returns true if the variable is bound AND has an error.
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     * println dfv.hasError()  // Prints: false (not bound)
     *
     * dfv.setError(new RuntimeException("Failed"))
     * println dfv.hasError()  // Prints: true
     * </pre>
     *
     * @return true if bound and has an error, false otherwise
     */
    boolean hasError () {
        isBound() && super.error != null
    }

    /**
     * Checks if this variable is done (bound to a value or error).
     * Alias for isBound() using CompletableFuture naming convention.
     *
     * @return true if this variable has been bound
     * @see DataflowExpression#isBound()
     */
    boolean isDone() {
        isBound()
    }

    /**
     * Retrieves the value with a timeout, blocking until available or timeout expires.
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     *
     * try {
     *     def result = dfv.get(1, TimeUnit.SECONDS)
     *     println result
     * } catch (TimeoutException e) {
     *     println "Timed out waiting for value"
     * }
     * </pre>
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the bound value of type T
     * @throws TimeoutException if the wait times out
     * @throws InterruptedException if the waiting thread is interrupted
     */
    T get(long timeout, TimeUnit unit) {
        try {
            return (T) getValue (timeout, unit)
        } catch (Exception ex) {
            setError(ex)
            //rethrow the timeout exception as well for tests
            throw ex
        }

    }

    /**
     * Alias for get(timeout, unit). Retrieves the value with a timeout.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the bound value of type T
     * @throws TimeoutException if the wait times out
     * @see #get(long, TimeUnit)
     */
    T getVal (long timeout, TimeUnit unit) {
        get (timeout, unit)
    }

    /**
     * Operator overload for left shift ({@code <<}) - provides syntactic sugar for setting values.
     *
     * <p>This enables a fluent, expressive syntax for binding dataflow variables that resembles
     * traditional assignment but maintains single-assignment semantics.</p>
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     * dfv << 42  // Equivalent to dfv.set(42)
     *
     * // Can also chain operations
     * def result = new DataflowVariable() << { 2 + 2 }
     * println result.get()  // Prints: 4
     * </pre>
     *
     * @param value the value to bind (can be direct value, Closure, or Callable)
     * @return this DataflowVariable for method chaining
     * @throws DataflowException if this variable has already been bound
     */
    DataflowVariable leftShift (value) {
        setValue (value)
        this
    }

    /**
     * Returns an Optional containing the value if bound, or an empty Optional if not yet bound.
     * This provides a non-blocking, type-safe way to check for and retrieve values.
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     *
     * Optional<Integer> maybe = dfv.getIfAvailable()
     * if (maybe.present) {
     *     println "Value: ${maybe.get()}"
     * } else {
     *     println "Not ready yet"
     * }
     *
     * dfv.set(42)
     * maybe = dfv.getIfAvailable()
     * println maybe.get()  // Prints: 42
     * </pre>
     *
     * @return an Optional containing the value if bound, or empty Optional if not bound
     */
    Optional<T> getIfAvailable() {
        return isBound() ? Optional.ofNullable(get()) : Optional.empty()
    }


    /**
     * Operator overload for right shift ({@code >>}) - registers a callback to be invoked when bound.
     *
     * <p>This provides a fluent, pipeline-like syntax for setting up dataflow callbacks. The callback
     * is invoked immediately if the variable is already bound, or queued for later execution if not.</p>
     *
     * <p>Unlike whenBound() which returns void, this method returns 'this' to enable chaining
     * multiple callbacks in a fluent style.</p>
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     *
     * dfv >> { println "First: $it" }
     *    >> { println "Second: $it" }
     *    >> { println "Third: $it" }
     *
     * dfv.set(42)
     * // Prints:
     * // First: 42
     * // Second: 42
     * // Third: 42
     * </pre>
     *
     * @param callable the callback to invoke (Closure, Consumer, or Callable)
     * @return this DataflowVariable for method chaining
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

    /**
     * Registers a Consumer callback to be invoked when this variable becomes available.
     *
     * <p>This method provides Java functional interface compatibility. The consumer is executed
     * immediately if the variable is already bound, or queued for later notification if not.</p>
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     *
     * dfv.whenAvailable(value -> {
     *     System.out.println("Received: " + value)
     * })
     *
     * dfv.set(42)  // Prints: "Received: 42"
     * </pre>
     *
     * @param callback the Consumer to invoke with the value when available
     */
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


    /**
     * Registers a Closure callback to be invoked when this variable becomes available.
     *
     * <p>This is the primary callback registration method for Groovy code. The closure receives
     * the bound value as its single parameter.</p>
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     *
     * dfv.whenAvailable { value ->
     *     println "Processing: $value"
     *     // Perform work with value
     * }
     *
     * dfv.set(42)  // Triggers the callback
     * </pre>
     *
     * @param callbackClosure the Closure to invoke with the value when available
     */
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

    /**
     * Creates a new DataflowVariable by applying a transformation to this variable's value.
     *
     * <p>This method extends the parent then() to provide additional type conversions and
     * supports Function, Consumer, and Callable in addition to Closure.</p>
     *
     * <p>The transformation types supported:
     * <ul>
     *   <li><b>Function:</b> Transforms value and returns new value</li>
     *   <li><b>Consumer:</b> Consumes value and returns original value</li>
     *   <li><b>Callable:</b> Ignores input and returns result of call()</li>
     *   <li><b>Closure:</b> Standard transformation closure</li>
     * </ul>
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     *
     * // Using Closure
     * def doubled = dfv.then { it * 2 }
     * def formatted = doubled.then { "Value: $it" }
     *
     * dfv.set(21)
     * println formatted.get()  // Prints: "Value: 42"
     *
     * // Using Function
     * Function<Integer, String> toHex = { i -> Integer.toHexString(i) }
     * def hex = dfv.then(toHex)
     * println hex.get()  // Prints: "15" (hex for 21)
     * </pre>
     *
     * @param callable the transformation to apply (Function, Consumer, Callable, or Closure)
     * @return a new DataflowVariable containing the transformed value
     */
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
     * Creates a new DataflowVariable that will attempt to recover from errors using a recovery function.
     *
     * <p>This enables error handling in dataflow pipelines without breaking the chain. If this
     * variable completes normally, the value is passed through. If it completes with an error,
     * the recovery function is invoked to produce an alternative value.</p>
     *
     * <h4>Example:</h4>
     * <pre>
     * def risky = new DataflowVariable()
     *
     * def safe = risky.recover { error ->
     *     log.warn "Operation failed: ${error.message}, using default"
     *     return 0  // Default value on error
     * }
     *
     * risky.setError(new RuntimeException("Network timeout"))
     * println safe.get()  // Prints: 0 (recovered value)
     *
     * // Chaining with recovery
     * def result = new DataflowVariable()
     *     .then { it / 2 }
     *     .recover { error ->
     *         error instanceof ArithmeticException ? 0 : throw error
     *     }
     * </pre>
     *
     * @param <R> the type of the recovered value
     * @param recovery a Function that takes a Throwable and produces a recovery value
     * @return a new DataflowVariable containing either the original value or the recovered value
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
     * Dynamic method handling for arithmetic operators via Groovy's methodMissing.
     *
     * <p>This enables natural arithmetic expressions between DataflowVariables and regular values.
     * Operations are evaluated asynchronously - the method returns immediately with a new
     * DataflowVariable that will be bound once both operands are available.</p>
     *
     * <p>Supported operators:
     * <ul>
     *   <li><b>plus</b> - Addition (+)</li>
     *   <li><b>minus</b> - Subtraction (-)</li>
     *   <li><b>multiply</b> - Multiplication (*)</li>
     *   <li><b>div</b> - Division (/)</li>
     *   <li><b>power</b> - Exponentiation (**)</li>
     *   <li><b>remainder</b> - Modulo (%)</li>
     * </ul>
     *
     * <h4>Example:</h4>
     * <pre>
     * def x = new DataflowVariable()
     * def y = new DataflowVariable()
     *
     * // Create computation graph before values are available
     * def sum = x.plus(y)           // x + y
     * def product = x.multiply(y)   // x * y
     * def mixed = x.plus(10)        // x + 10 (mixing DFV and literal)
     *
     * // Bind values - calculations happen asynchronously
     * x.set(5)
     * y.set(3)
     *
     * println sum.get()      // Prints: 8
     * println product.get()  // Prints: 15
     * println mixed.get()    // Prints: 15
     *
     * // Errors propagate through the chain
     * def z = new DataflowVariable()
     * def result = z.div(0)  // Division by zero
     * z.set(10)
     * try {
     *     result.get()
     * } catch (ArithmeticException e) {
     *     println "Caught: ${e.message}"
     * }
     * </pre>
     *
     * @param name the method name (e.g., 'plus', 'minus', 'multiply')
     * @param args the method arguments (first arg is the right operand)
     * @return a new DataflowVariable containing the result of the operation
     * @throws MissingMethodException if the method is not a supported operator
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
     * Dynamic property handling via Groovy's propertyMissing for property-style method calls.
     *
     * <p>This allows calling methods on the bound value without explicitly calling get() first.
     * Useful for conversions and property access on the underlying value.</p>
     *
     * <h4>Example:</h4>
     * <pre>
     * def dfv = new DataflowVariable()
     * dfv.set("42")
     *
     * // Can call toInteger() directly
     * def number = dfv.toInteger()
     * println number  // Prints: 42
     *
     * // Equivalent to:
     * def number2 = dfv.get().toInteger()
     * </pre>
     *
     * @param name the property/method name to invoke on the bound value
     * @return the result of invoking the property/method on the bound value
     * @throws MissingPropertyException if the property doesn't exist on the bound value
     */
    def propertyMissing(String name) {
        // For operations like toInteger(), toString(), etc.
        this.get()."$name"()
    }

}