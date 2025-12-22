package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests for ScriptTask - multi-language script execution.
 */
class ScriptTaskTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testGroovyScriptExecution() {
        def task = new ScriptTask("groovy-test", "Groovy Script", ctx)
        
        task.language = "groovy"
        task.script = """
            def total = items.sum { it.price * it.quantity }
            return [total: total, count: items.size()]
        """
        task.bindings { taskCtx, prev ->
            [items: [
                [price: 10, quantity: 2],
                [price: 5, quantity: 3]
            ]]
        }
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertEquals(35, result.total)
        assertEquals(2, result.count)
        assertEquals(TaskState.COMPLETED, task.state)
        
        println "✅ Groovy script execution test passed"
    }

    // JavaScript tests commented out - requires GraalVM or Nashorn
    /*
    @Test
    void testJavaScriptExecution() {
        def task = new ScriptTask("js-test", "JavaScript Script", ctx)
        
        task.language = "javascript"
        task.script = """
            function calculate(a, b) {
                return a + b;
            }
            calculate(x, y);
        """
        task.bindings { taskCtx, prev ->
            [x: 10, y: 20]
        }
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertEquals(30.0, result as Double, 0.001)
        assertEquals(TaskState.COMPLETED, task.state)
        
        println "✅ JavaScript execution test passed"
    }
    */

    @Test
    void testDefaultBindings() {
        def task = new ScriptTask("bindings-test", "Bindings Test", ctx)
        
        task.script = """
            return [
                hasCtx: ctx != null,
                hasPrev: prev != null,
                hasInput: input != null,
                hasContext: context != null,
                prevValue: prev
            ]
        """
        
        def prevPromise = ctx.promiseFactory.createPromise([value: 42])
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertTrue(result.hasCtx)
        assertTrue(result.hasPrev)
        assertTrue(result.hasInput)
        assertTrue(result.hasContext)
        assertEquals(42, result.prevValue.value)
        
        println "✅ Default bindings test passed"
    }

    @Test
    void testScriptCaching() {
        def task = new ScriptTask("cache-test", "Cache Test", ctx)
        
        task.script = """
            def value = input * 2
            return value
        """
        task.bindings { taskCtx, prev -> [input: prev] }
        
        // Execute twice - second should use cached script
        def promise1 = task.execute(ctx.promiseFactory.createPromise(5))
        def result1 = awaitPromise(promise1)
        assertEquals(10, result1)
        
        // Reset state for second execution
        task.state = TaskState.SCHEDULED
        task.completionPromise = null
        
        def promise2 = task.execute(ctx.promiseFactory.createPromise(7))
        def result2 = awaitPromise(promise2)
        assertEquals(14, result2)
        
        println "✅ Script caching test passed"
    }

    @Test
    void testScriptError() {
        def task = new ScriptTask("error-test", "Error Test", ctx)
        
        task.script = """
            throw new RuntimeException("Script error")
        """
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        
        assertThrows(Exception.class) {
            awaitPromise(promise)
        }
        
        assertEquals(TaskState.FAILED, task.state)
        assertNotNull(task.error)
        
        println "✅ Script error handling test passed"
    }

    @Test
    void testMissingScript() {
        def task = new ScriptTask("missing-test", "Missing Script", ctx)
        // Don't set script
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        
        assertThrows(Exception.class) {
            awaitPromise(promise)
        }
        
        println "✅ Missing script validation test passed"
    }

    @Test
    void testComplexDataTransformation() {
        def task = new ScriptTask("transform-test", "Transform Test", ctx)
        
        task.script = """
            def people = data.people
            def adults = people.findAll { it.age >= 18 }
            return [
                totalPeople: people.size(),
                adults: adults.size(),
                minors: people.size() - adults.size(),
                averageAge: people.sum { it.age } / people.size()
            ]
        """
        task.bindings { taskCtx, prev ->
            [data: [
                people: [
                    [name: "Alice", age: 25],
                    [name: "Bob", age: 17],
                    [name: "Charlie", age: 30],
                    [name: "Diana", age: 16]
                ]
            ]]
        }
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertEquals(4, result.totalPeople)
        assertEquals(2, result.adults)
        assertEquals(2, result.minors)
        assertEquals(22, result.averageAge as Integer)
        
        println "✅ Complex data transformation test passed"
    }

    /*
    @Test
    void testJavaScriptStringManipulation() {
        def task = new ScriptTask("js-string-test", "JS String Test", ctx)
        
        task.language = "javascript"
        task.script = """
            var result = {
                upper: name.toUpperCase(),
                lower: name.toLowerCase(),
                length: name.length,
                reversed: name.split('').reverse().join('')
            };
            result;
        """
        task.bindings { taskCtx, prev ->
            [name: "JavaScript"]
        }
        
        def prevPromise = ctx.promiseFactory.createPromise(null)
        def promise = task.execute(prevPromise)
        def result = awaitPromise(promise)
        
        assertEquals("JAVASCRIPT", result.upper)
        assertEquals("javascript", result.lower)
        assertEquals(10, result.length)
        assertEquals("tpircSavaJ", result.reversed)
        
        println "✅ JavaScript string manipulation test passed"
    }
    */
}
