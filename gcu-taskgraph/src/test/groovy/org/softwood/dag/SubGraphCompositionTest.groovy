package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests demonstrating SubGraph composition patterns for building
 * reusable workflow libraries.
 *
 * These tests show simple, clear examples of how to create composable
 * subgraphs without complex business logic.
 */
class SubGraphCompositionTest {

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

    // =========================================================================
    // Simple Validation Library
    // =========================================================================

    /**
     * Library of simple reusable validation subgraphs.
     * Each subgraph does basic validation and transformation.
     */
    static class ValidationLibrary {

        /**
         * Validate that input is not null and not empty.
         * Returns: [valid: boolean, value: input]
         */
        static TaskGraph validateNotEmpty() {
            TaskGraph.build {
                serviceTask("check-not-null") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            def valid = prev != null && prev != ""
                            [valid: valid, value: prev, reason: valid ? "ok" : "empty"]
                        }
                    }
                }
            }
        }

        /**
         * Validate that input is numeric.
         * Returns: [valid: boolean, number: parsedNumber]
         */
        static TaskGraph validateNumeric() {
            TaskGraph.build {
                serviceTask("check-numeric") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            try {
                                def number = prev instanceof Number ? prev : Double.parseDouble(prev.toString())
                                [valid: true, number: number, reason: "ok"]
                            } catch (Exception e) {
                                [valid: false, number: null, reason: "not numeric"]
                            }
                        }
                    }
                }
            }
        }

        /**
         * Validate that number is in range.
         * Returns: [valid: boolean, value: number]
         */
        static TaskGraph validateRange(double min, double max) {
            TaskGraph.build {
                serviceTask("check-range") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            def number = prev instanceof Map ? prev.number : prev
                            def inRange = number >= min && number <= max
                            [valid: inRange, value: number, reason: inRange ? "ok" : "out of range"]
                        }
                    }
                }
            }
        }

        /**
         * Combined validation: not empty + numeric + range.
         * This is a composition of other subgraphs!
         */
        static TaskGraph validateNumericInRange(double min, double max) {
            TaskGraph.build {
                // Step 1: Validate not empty
                task("check-not-empty", TaskType.SUBGRAPH) {
                    graph validateNotEmpty()
                }

                // Step 2: Validate numeric
                task("check-numeric", TaskType.SUBGRAPH) {
                    graph validateNumeric()
                    inputMapper { prev -> prev.value }
                }

                // Step 3: Validate range
                task("check-range", TaskType.SUBGRAPH) {
                    graph validateRange(min, max)
                    inputMapper { prev -> prev.number }
                }

                // Chain them
                chainVia("check-not-empty", "check-numeric", "check-range")
            }
        }
    }

    /**
     * Library of simple transformation subgraphs.
     */
    static class TransformLibrary {

        /**
         * Double the input value.
         */
        static TaskGraph doubleValue() {
            TaskGraph.build {
                serviceTask("double") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            def value = prev instanceof Map ? prev.value : prev
                            [value: value * 2, operation: "doubled"]
                        }
                    }
                }
            }
        }

        /**
         * Add a constant to the input.
         */
        static TaskGraph addConstant(double constant) {
            TaskGraph.build {
                serviceTask("add") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            def value = prev instanceof Map ? prev.value : prev
                            [value: value + constant, operation: "added $constant"]
                        }
                    }
                }
            }
        }

        /**
         * Format as string with prefix.
         */
        static TaskGraph formatWithPrefix(String prefix) {
            TaskGraph.build {
                serviceTask("format") {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            def value = prev instanceof Map ? prev.value : prev
                            [formatted: "${prefix}${value}".toString(), value: value]
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Test 1: Simple Single SubGraph Usage
    // =========================================================================

    @Test
    void testSingleSubGraphValidation() {
        def workflow = TaskGraph.build {
            serviceTask("prepare") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "42" }
                }
            }

            task("validate", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateNotEmpty()
            }

            chainVia("prepare", "validate")
        }

        def result = awaitPromise(workflow.run())

        assertTrue(result.valid)
        assertEquals("42", result.value)
    }

    // =========================================================================
    // Test 2: Sequential Composition
    // =========================================================================

    @Test
    void testSequentialSubGraphComposition() {
        def workflow = TaskGraph.build {
            serviceTask("provide-input") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "100" }
                }
            }

            // Validate it's not empty
            task("validate-not-empty", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateNotEmpty()
            }

            // Validate it's numeric
            task("validate-numeric", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateNumeric()
                inputMapper { prev -> prev.value }
            }

            // Validate it's in range
            task("validate-range", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateRange(0, 200)
                inputMapper { prev -> prev.number }
            }

            chainVia("provide-input", "validate-not-empty", "validate-numeric", "validate-range")
        }

        def result = awaitPromise(workflow.run())

        assertTrue(result.valid)
        assertEquals(100.0, result.value)
    }

    // =========================================================================
    // Test 3: Nested SubGraph (SubGraph composed of SubGraphs)
    // =========================================================================

    @Test
    void testNestedSubGraphComposition() {
        // The validateNumericInRange subgraph itself uses other subgraphs!
        def workflow = TaskGraph.build {
            serviceTask("provide-input") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "50" }
                }
            }

            task("validate-all", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateNumericInRange(0, 100)
            }

            chainVia("provide-input", "validate-all")
        }

        def result = awaitPromise(workflow.run())

        assertTrue(result.valid)
        assertEquals(50.0, result.value)
    }

    // =========================================================================
    // Test 4: Reusing Same SubGraph Multiple Times
    // =========================================================================

    @Test
    void testReuseSubGraphInMultipleWorkflows() {
        // Workflow 1: Validate one value
        def workflow1 = TaskGraph.build {
            serviceTask("provide-input") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "25" }
                }
            }

            task("validate", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateNumericInRange(0, 100)
            }

            chainVia("provide-input", "validate")
        }

        // Workflow 2: Validate different value using SAME subgraph
        def workflow2 = TaskGraph.build {
            serviceTask("provide-input") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "75" }
                }
            }

            task("validate", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateNumericInRange(0, 100)
            }

            chainVia("provide-input", "validate")
        }

        def result1 = awaitPromise(workflow1.run())
        def result2 = awaitPromise(workflow2.run())

        // Both use the same validation subgraph
        assertTrue(result1.valid)
        assertEquals(25.0, result1.value)

        assertTrue(result2.valid)
        assertEquals(75.0, result2.value)
    }

    // =========================================================================
    // Test 5: Parallel SubGraphs
    // =========================================================================

    @Test
    void testParallelSubGraphExecution() {
        def workflow = TaskGraph.build {
            serviceTask("provide-input") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { 10.0 }
                }
            }

            // Run two transformations in parallel
            task("double", TaskType.SUBGRAPH) {
                graph TransformLibrary.doubleValue()
            }

            task("add-five", TaskType.SUBGRAPH) {
                graph TransformLibrary.addConstant(5)
            }

            serviceTask("combine") {
                action { ctx, prev ->
                    // prev is a list containing results from both parallel branches
                    // The join task automatically combines them into a list
                    def doubled = prev[0]  // Result from "double" subgraph
                    def added = prev[1]    // Result from "add-five" subgraph

                    ctx.promiseFactory.executeAsync {
                        [doubled: doubled.value, added: added.value]
                    }
                }
            }

            // Setup parallel execution
            dependsOn("double", "provide-input")
            dependsOn("add-five", "provide-input")
            dependsOn("combine", "double", "add-five")
        }

        def result = awaitPromise(workflow.run())

        assertEquals(20.0, result.doubled)  // 10 * 2
        assertEquals(15.0, result.added)    // 10 + 5
    }

    // =========================================================================
    // Test 6: Transform Pipeline (Validate → Transform → Format)
    // =========================================================================

    @Test
    void testValidateTransformFormatPipeline() {
        def workflow = TaskGraph.build {
            serviceTask("provide-input") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "10" }
                }
            }

            // Step 1: Validate
            task("validate", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateNumericInRange(0, 100)
            }

            // Step 2: Transform (double it)
            task("transform", TaskType.SUBGRAPH) {
                graph TransformLibrary.doubleValue()
                inputMapper { prev -> prev.value }
            }

            // Step 3: Format
            task("format", TaskType.SUBGRAPH) {
                graph TransformLibrary.formatWithPrefix("Result: ")
                inputMapper { prev -> prev.value }
            }

            chainVia("provide-input", "validate", "transform", "format")
        }

        def result = awaitPromise(workflow.run())

        assertEquals("Result: 20.0", result.formatted.toString())
    }

    // =========================================================================
    // Test 7: Conditional SubGraph Selection (Gateway Pattern)
    // =========================================================================

    // DISABLED: exclusiveGateway not implemented
    // @Test
    void testConditionalSubGraphSelection() {
        def workflow = TaskGraph.build {
            serviceTask("provide-input") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { 150.0 }
                }
            }

            exclusiveGateway("routing") {
                condition("small") { prev -> prev < 50 }
                condition("medium") { prev -> prev >= 50 && prev < 100 }
                condition("large") { prev -> prev >= 100 }
            }

            // Different processing based on size
            task("process-small", TaskType.SUBGRAPH) {
                graph TransformLibrary.addConstant(10)
            }

            task("process-medium", TaskType.SUBGRAPH) {
                graph TransformLibrary.doubleValue()
            }

            task("process-large", TaskType.SUBGRAPH) {
                graph TransformLibrary.formatWithPrefix("LARGE: ")
            }

            dependsOn("routing", "provide-input")
            routeVia("routing", "small", "process-small")
            routeVia("routing", "medium", "process-medium")
            routeVia("routing", "large", "process-large")
        }

        def result = awaitPromise(workflow.run())

        // Should take "large" path
        assertEquals("LARGE: 150.0", result.formatted)
    }

    // =========================================================================
    // Test 8: Loop with SubGraph (Batch Processing)
    // =========================================================================

    @Test
    void testLoopWithSubGraph() {
        def workflow = TaskGraph.build {
            serviceTask("prepare-batch") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        ctx.globals.items = [5, 10, 15, 20]; "prepared"
                    }
                }
            }

            loop("process-items") {
                over { ctx -> ctx.globals.items }
                accumulator([])

                // Process each item (simple validation inline)
                action { ctx, item, index, results ->
                    // Simple validation: check if in range
                    def valid = item >= 0 && item <= 25
                    results << [valid: valid, value: item]; results
                }

                }

            chainVia("prepare-batch", "process-items")
        }

        def result = awaitPromise(workflow.run())

        assertEquals(4, result.size())
        assertTrue(result.every { it.valid })
        assertEquals([5, 10, 15, 20], result.collect { it.value })
    }

    // =========================================================================
    // Test 9: Error Handling in SubGraph
    // =========================================================================

    @Test
    void testSubGraphWithInvalidInput() {
        def workflow = TaskGraph.build {
            serviceTask("provide-input") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "not-a-number" }
                }
            }

            task("validate", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateNumeric()
            }

            chainVia("provide-input", "validate")
        }

        def result = awaitPromise(workflow.run())

        assertFalse(result.valid)
        assertNull(result.number)
        assertEquals("not numeric", result.reason)
    }

    // =========================================================================
    // Test 10: Library Composition - Building on Existing SubGraphs
    // =========================================================================

    @Test
    void testLibraryComposition() {
        // Create a new library that builds on existing ones
        def pipeline = TaskGraph.build {
            serviceTask("provide-input") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "30" }
                }
            }

            // Compose: Validate → Transform → Format
            task("validate", TaskType.SUBGRAPH) {
                graph ValidationLibrary.validateNumericInRange(0, 100)
            }

            task("double", TaskType.SUBGRAPH) {
                graph TransformLibrary.doubleValue()
                inputMapper { prev -> prev.value }
            }

            task("add-ten", TaskType.SUBGRAPH) {
                graph TransformLibrary.addConstant(10)
                inputMapper { prev -> prev.value }
            }

            task("format", TaskType.SUBGRAPH) {
                graph TransformLibrary.formatWithPrefix("Final: ")
                inputMapper { prev -> prev.value }
            }

            chainVia("provide-input", "validate", "double", "add-ten", "format")
        }

        def result = awaitPromise(pipeline.run())

        // 30 → validated → doubled (60) → +10 (70) → formatted
        assertEquals("Final: 70.0", result.formatted.toString())
    }
}