package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.softwood.dag.task.*
import java.util.function.Function
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Tests for DataTransformTask - Functional Data Transformation Pipeline
 */
class DataTransformTaskTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    // =========================================================================
    // Basic Transformation Tests
    // =========================================================================

    @Test
    void testSimpleTransform() {
        def task = new DataTransformTask("transform", "Transform", ctx)

        task.transform { data ->
            [processed: true, value: data.value * 2]
        }

        def promise = task.execute(ctx.promiseFactory.createPromise([value: 10]))
        def result = promise.get()

        assertTrue(result.processed)
        assertEquals(20, result.value)
    }

    @Test
    void testMultipleTransforms() {
        def task = new DataTransformTask("chain", "Chain", ctx)

        // Chain multiple transformations
        task.transform { data -> data * 2 }
        task.transform { data -> data + 10 }
        task.transform { data -> data * 3 }

        def promise = task.execute(ctx.promiseFactory.createPromise(5))
        def result = promise.get()

        // (5 * 2) = 10, (10 + 10) = 20, (20 * 3) = 60
        assertEquals(60, result)
    }

    @Test
    void testMapTransform() {
        def task = new DataTransformTask("map", "Map", ctx)

        task.map { user ->
            [
                    id: user.id,
                    fullName: "${user.firstName} ${user.lastName}".toUpperCase(),
                    active: user.status == 'ACTIVE'
            ]
        }

        def users = [
                [id: 1, firstName: 'John', lastName: 'Doe', status: 'ACTIVE'],
                [id: 2, firstName: 'Jane', lastName: 'Smith', status: 'INACTIVE']
        ]

        def promise = task.execute(ctx.promiseFactory.createPromise(users))
        def result = promise.get()

        assertEquals(2, result.size())
        assertEquals('JOHN DOE', result[0].fullName)
        assertTrue(result[0].active)
        assertEquals('JANE SMITH', result[1].fullName)
        assertFalse(result[1].active)
    }

    // =========================================================================
    // Tap (Inspection) Tests
    // =========================================================================

    @Test
    void testTapInspection() {
        def tappedValues = []

        def task = new DataTransformTask("tap", "Tap", ctx)

        task.transform { data -> data * 2 }
        task.tap { data -> tappedValues << data }
        task.transform { data -> data + 5 }
        task.tap { data -> tappedValues << data }

        def promise = task.execute(ctx.promiseFactory.createPromise(10))
        def result = promise.get()

        // Final result: (10 * 2) + 5 = 25
        assertEquals(25, result)

        // Tap points captured intermediate values
        assertEquals(2, tappedValues.size())
        assertEquals(20, tappedValues[0])  // After first transform
        assertEquals(25, tappedValues[1])  // After second transform
    }

    @Test
    void testNamedTap() {
        def tapLog = [:]

        def task = new DataTransformTask("named-tap", "NamedTap", ctx)

        task.transform { data -> data.toUpperCase() }
        task.tap("after uppercase") { data -> tapLog['uppercase'] = data }

        task.transform { data -> data.reverse() }
        task.tap("after reverse") { data -> tapLog['reverse'] = data }

        def promise = task.execute(ctx.promiseFactory.createPromise("hello"))
        def result = promise.get()

        assertEquals("OLLEH", result)
        assertEquals("HELLO", tapLog['uppercase'])
        assertEquals("OLLEH", tapLog['reverse'])
    }

    // =========================================================================
    // Filter Tests
    // =========================================================================

    @Test
    void testFilterPassing() {
        def task = new DataTransformTask("filter-pass", "FilterPass", ctx)

        task.transform { data -> data * 2 }
        task.filter { data -> data > 10 }
        task.transform { data -> data + 100 }

        def promise = task.execute(ctx.promiseFactory.createPromise(10))
        def result = promise.get()

        // (10 * 2) = 20, passes filter, + 100 = 120
        assertEquals(120, result)
    }

    @Test
    void testFilterFailing() {
        def task = new DataTransformTask("filter-fail", "FilterFail", ctx)

        task.transform { data -> data * 2 }
        task.filter { data -> data > 100 }  // Will fail (20 < 100)
        // No more transforms - test null propagation only

        def promise = task.execute(ctx.promiseFactory.createPromise(10))
        def result = promise.get()

        // (10 * 2) = 20, fails filter, result is null
        assertNull(result)
    }

    @Test
    void testStopOnFilterFail() {
        def task = new DataTransformTask("stop-filter", "StopFilter", ctx)

        task.stopOnFilterFail(true)

        def transformCalled = false

        task.filter { data -> data > 100 }  // Will fail
        task.transform { data ->
            transformCalled = true
            data + 100
        }

        def promise = task.execute(ctx.promiseFactory.createPromise(10))
        def result = promise.get()

        // Pipeline stopped after filter failed
        assertNull(result)
        assertFalse(transformCalled)
    }

    // =========================================================================
    // Reduce Tests
    // =========================================================================

    @Test
    void testReduceCollection() {
        def task = new DataTransformTask("reduce", "Reduce", ctx)

        task.reduce(0) { acc, item ->
            acc + item
        }

        def numbers = [1, 2, 3, 4, 5]

        def promise = task.execute(ctx.promiseFactory.createPromise(numbers))
        def result = promise.get()

        assertEquals(15, result)  // Sum of 1+2+3+4+5
    }

    @Test
    void testReduceToMap() {
        def task = new DataTransformTask("reduce-map", "ReduceMap", ctx)

        task.reduce([total: 0, count: 0]) { acc, user ->
            acc.total += user.age
            acc.count++
            acc
        }

        def users = [
                [name: 'Alice', age: 30],
                [name: 'Bob', age: 25],
                [name: 'Charlie', age: 35]
        ]

        def promise = task.execute(ctx.promiseFactory.createPromise(users))
        def result = promise.get()

        assertEquals(90, result.total)
        assertEquals(3, result.count)
    }

    // =========================================================================
    // FlatMap Tests
    // =========================================================================

    @Test
    void testFlatMap() {
        def task = new DataTransformTask("flatmap", "FlatMap", ctx)

        task.flatMap { dept ->
            dept.employees  // Each dept has list of employees
        }

        def departments = [
                [name: 'Engineering', employees: ['Alice', 'Bob', 'Charlie']],
                [name: 'Sales', employees: ['David', 'Eve']]
        ]

        def promise = task.execute(ctx.promiseFactory.createPromise(departments))
        def result = promise.get()

        // Flattened list of all employees
        assertEquals(5, result.size())
        assertTrue(result.contains('Alice'))
        assertTrue(result.contains('David'))
    }

    // =========================================================================
    // Complex Pipeline Tests
    // =========================================================================

    @Test
    void testComplexPipeline() {
        def tapPoints = []

        def task = new DataTransformTask("complex", "Complex", ctx)

        // Extract and normalize user data
        task.map { user ->
            [
                    id: user.id,
                    name: user.name.toLowerCase(),
                    age: user.age,
                    active: user.status == 'ACTIVE'
            ]
        }

        task.tap("after normalization") { users -> tapPoints << "normalized: ${users.size()}".toString() }

        // Filter active users only
        task.transform { users ->
            users.findAll { it.active }
        }

        task.tap("after filter") { users -> tapPoints << "filtered: ${users.size()}".toString() }

        // Calculate statistics
        task.reduce([count: 0, totalAge: 0, avgAge: 0]) { acc, user ->
            acc.count++
            acc.totalAge += user.age
            acc
        }

        // Calculate average
        task.transform { stats ->
            stats.avgAge = stats.count > 0 ? stats.totalAge / stats.count : 0
            stats
        }

        def users = [
                [id: 1, name: 'ALICE', age: 30, status: 'ACTIVE'],
                [id: 2, name: 'BOB', age: 25, status: 'INACTIVE'],
                [id: 3, name: 'CHARLIE', age: 35, status: 'ACTIVE']
        ]

        def promise = task.execute(ctx.promiseFactory.createPromise(users))
        def result = promise.get()

        assertEquals(2, result.count)
        assertEquals(65, result.totalAge)
        assertEquals(32.5, result.avgAge, 0.01)

        assertEquals(2, tapPoints.size())
        assertEquals("normalized: 3", tapPoints[0])
        assertEquals("filtered: 2", tapPoints[1])
    }

    // =========================================================================
    // Java Functional Interface Tests
    // =========================================================================

    @Test
    void testJavaFunctionInterface() {
        Function<Integer, Integer> doubler = { x -> x * 2 }

        def task = new DataTransformTask("java-func", "JavaFunc", ctx)
        task.transform(doubler)

        def promise = task.execute(ctx.promiseFactory.createPromise(10))
        def result = promise.get()

        assertEquals(20, result)
    }

    @Test
    void testJavaConsumerTap() {
        def tappedValue = null
        Consumer<Integer> tap = { value -> tappedValue = value }

        def task = new DataTransformTask("java-consumer", "JavaConsumer", ctx)

        task.transform { x -> x * 2 }
        task.tap(tap)

        def promise = task.execute(ctx.promiseFactory.createPromise(10))
        promise.get()

        assertEquals(20, tappedValue)
    }

    @Test
    void testJavaPredicateFilter() {
        Predicate<Integer> greaterThan10 = { x -> x > 10 }

        def task = new DataTransformTask("java-predicate", "JavaPredicate", ctx)

        task.transform { x -> x * 2 }
        task.filter(greaterThan10)

        def promise1 = task.execute(ctx.promiseFactory.createPromise(3))
        def result1 = promise1.get()
        assertNull(result1)  // 3*2=6, fails filter

        // Clear and rebuild pipeline for second test
        task.clearPipeline()
        task.transform { x -> x * 2 }
        task.filter(greaterThan10)

        def promise2 = task.execute(ctx.promiseFactory.createPromise(10))
        def result2 = promise2.get()
        assertEquals(20, result2)  // 10*2=20, passes filter
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Test
    void testTransformException() {
        def task = new DataTransformTask("error", "Error", ctx)

        task.transform { data -> data * 2 }
        task.transform { data ->
            throw new RuntimeException("Transform failed!")
        }

        def promise = task.execute(ctx.promiseFactory.createPromise(10))

        // Exception should be thrown when promise.get() is called
        def exception = assertThrows(RuntimeException.class) {
            promise.get()
        }
        
        // Verify it's the TransformException wrapping the original error
        // Note: No retry wrapping since maxAttempts defaults to 0
        assertTrue(exception.message.contains("Transform step") || exception.message.contains("Transform failed!"))
    }

    @Test
    void testErrorRecovery() {
        def task = new DataTransformTask("recovery", "Recovery", ctx)

        task.transform { data -> data * 2 }
        task.transform { data ->
            throw new RuntimeException("Transform failed!")
        }

        task.onError { error, originalData ->
            [
                    status: 'FAILED',
                    error: error.message,
                    originalData: originalData,
                    recovered: true
            ]
        }

        def promise = task.execute(ctx.promiseFactory.createPromise(10))
        def result = promise.get()

        assertEquals('FAILED', result.status)
        assertTrue(result.recovered)
        assertEquals(10, result.originalData)
    }

    // =========================================================================
    // Pipeline Introspection Tests
    // =========================================================================

    @Test
    void testPipelineSize() {
        def task = new DataTransformTask("size", "Size", ctx)

        assertEquals(0, task.pipelineSize)

        task.transform { data -> data * 2 }
        assertEquals(1, task.pipelineSize)

        task.tap { data -> println(data) }
        assertEquals(2, task.pipelineSize)

        task.filter { data -> data > 10 }
        assertEquals(3, task.pipelineSize)
    }

    @Test
    void testPipelineSteps() {
        def task = new DataTransformTask("steps", "Steps", ctx)

        task.transform { data -> data * 2 }
        task.tap("checkpoint") { data -> println(data) }
        task.filter { data -> data > 10 }
        task.reduce(0) { acc, item -> acc + item }

        def steps = task.pipelineSteps

        assertEquals(4, steps.size())
        assertTrue(steps[0].contains("TRANSFORM"))
        assertTrue(steps[1].contains("TAP"))
        assertTrue(steps[1].contains("checkpoint"))
        assertTrue(steps[2].contains("FILTER"))
        assertTrue(steps[3].contains("REDUCE"))
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Test
    void testEmptyPipeline() {
        def task = new DataTransformTask("empty", "Empty", ctx)

        // No transformations defined
        def promise = task.execute(ctx.promiseFactory.createPromise(42))
        def result = promise.get()

        // Should return input unchanged
        assertEquals(42, result)
    }

    @Test
    void testMapOnSingleItem() {
        def task = new DataTransformTask("map-single", "MapSingle", ctx)

        task.map { x -> x * 2 }

        // Map on single item (not a collection)
        def promise = task.execute(ctx.promiseFactory.createPromise(10))
        def result = promise.get()

        assertEquals(20, result)
    }

    @Test
    void testMapOnMap() {
        def task = new DataTransformTask("map-on-map", "MapOnMap", ctx)

        task.map { value -> value * 2 }

        def inputMap = [a: 10, b: 20, c: 30]

        def promise = task.execute(ctx.promiseFactory.createPromise(inputMap))
        def result = promise.get()

        assertEquals(20, result.a)
        assertEquals(40, result.b)
        assertEquals(60, result.c)
    }

    // =========================================================================
    // DSL Integration Test
    // =========================================================================

    @Test
    void testInTaskGraph() {
        def graph = TaskGraph.build {
            serviceTask("fetch-users") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [
                                [id: 1, name: 'Alice', age: 30, dept: 'Engineering'],
                                [id: 2, name: 'Bob', age: 25, dept: 'Sales'],
                                [id: 3, name: 'Charlie', age: 35, dept: 'Engineering']
                        ]
                    }
                }
            }

            task("process-users", TaskType.DATA_TRANSFORM) {
                // Filter engineers
                transform { users ->
                    users.findAll { it.dept == 'Engineering' }
                }

                // Calculate average age
                reduce([count: 0, totalAge: 0]) { acc, user ->
                    acc.count++
                    acc.totalAge += user.age
                    acc
                }

                // Add average
                transform { stats ->
                    stats.avgAge = stats.totalAge / stats.count
                    stats
                }
            }

            // Simple linear dependency using new DSL
            dependsOn("process-users", "fetch-users")
        }

        def result = graph.run().get()

        assertEquals(2, result.count)
        assertEquals(32.5, result.avgAge, 0.01)
    }
}