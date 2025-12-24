package org.softwood.dag.task

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.softwood.dag.TaskGraph

/**
 * Tests for LoopTask - iteration over collections
 */
class LoopTaskTest {

    TaskContext ctx
    
    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    // =========================================================================
    // Basic Sequential Iteration Tests
    // =========================================================================

    @Test
    void testSimpleSequentialIteration() {
        def loop = TaskFactory.createLoopTask("loop1", "Simple Loop", ctx)
        loop.over { ctx -> [1, 2, 3, 4, 5] }
        loop.action { ctx, item, index ->
            item * 2
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals([2, 4, 6, 8, 10], result)
    }

    @Test
    void testIterationWithIndex() {
        def items = []
        
        def loop = TaskFactory.createLoopTask("loop1", "Index Loop", ctx)
        loop.over { ctx -> ['a', 'b', 'c'] }
        loop.action { ctx, item, index ->
            items << [item: item, index: index]
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals(3, items.size())
        assertEquals('a', items[0].item)
        assertEquals(0, items[0].index)
        assertEquals('c', items[2].item)
        assertEquals(2, items[2].index)
    }

    @Test
    void testEmptyCollection() {
        def loop = TaskFactory.createLoopTask("loop1", "Empty Loop", ctx)
        loop.over { ctx -> [] }
        loop.action { ctx, item, index ->
            fail("Should not be called")
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals([], result)
    }

    @Test
    void testNullCollection() {
        def loop = TaskFactory.createLoopTask("loop1", "Null Loop", ctx)
        loop.over { ctx -> null }
        loop.action { ctx, item, index ->
            fail("Should not be called")
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals([], result)
    }

    // =========================================================================
    // Accumulator Pattern Tests
    // =========================================================================

    @Test
    void testAccumulatorSum() {
        def loop = TaskFactory.createLoopTask("loop1", "Sum Loop", ctx)
        loop.over { ctx -> [1, 2, 3, 4, 5] }
        loop.accumulator(0)
        loop.action { ctx, item, index, acc ->
            acc + item
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals(15, result)
    }

    @Test
    void testAccumulatorMap() {
        def loop = TaskFactory.createLoopTask("loop1", "Accumulator Map", ctx)
        loop.over { ctx -> [
            [name: "Alice", age: 30],
            [name: "Bob", age: 25],
            [name: "Charlie", age: 35]
        ]}
        loop.accumulator([total: 0, count: 0])
        loop.action { ctx, person, index, acc ->
            acc.total += person.age
            acc.count++
            acc
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals(90, result.total)
        assertEquals(3, result.count)
        assertEquals(30.0, result.total / result.count, 0.01)
    }

    // =========================================================================
    // Break Condition Tests
    // =========================================================================

    @Test
    void testBreakWhenCondition() {
        def processed = []
        
        def loop = TaskFactory.createLoopTask("loop1", "Break Loop", ctx)
        loop.over { ctx -> [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] }
        loop.breakWhen { ctx, item, index -> item > 5 }
        loop.action { ctx, item, index ->
            processed << item
            item
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        // Should process 1-5, then break on 6
        assertEquals([1, 2, 3, 4, 5], processed)
    }

    @Test
    void testBreakReturnFirstMatch() {
        def loop = TaskFactory.createLoopTask("loop1", "Find Loop", ctx)
        loop.over { ctx -> [
            [id: 1, name: "Alice"],
            [id: 2, name: "Bob"],
            [id: 3, name: "Charlie"]
        ]}
        loop.breakWhen { ctx, item, index -> item.id == 2 }
        loop.action { ctx, item, index ->
            item
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        // Result mode should be FIRST_MATCH when breakWhen is used
        assertEquals(2, result.id)
        assertEquals("Bob", result.name)
    }

    // =========================================================================
    // Parallel Execution Tests
    // =========================================================================

    @Test
    void testParallelExecution() {
        def loop = TaskFactory.createLoopTask("loop1", "Parallel Loop", ctx)
        loop.over { ctx -> (1..10).toList() }
        loop.parallel(3)  // Process 3 at a time
        loop.action { ctx, item, index ->
            // Simulate some work
            Thread.sleep(10)
            item * 2
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals(10, result.size())
        assertTrue(result.contains(2))
        assertTrue(result.contains(20))
    }

    @Test
    void testParallelWithDefaultParallelism() {
        def loop = TaskFactory.createLoopTask("loop1", "Parallel Loop", ctx)
        loop.over { ctx -> (1..5).toList() }
        loop.parallel(true)  // Use default (CPU cores)
        loop.action { ctx, item, index ->
            item * 2
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals(5, result.size())
        assertEquals([2, 4, 6, 8, 10], result)
    }

    @Test
    void testParallelFallsBackToSequentialWithAccumulator() {
        def loop = TaskFactory.createLoopTask("loop1", "Parallel with Accumulator", ctx)
        loop.over { ctx -> [1, 2, 3, 4, 5] }
        loop.parallel(3)
        loop.accumulator(0)
        loop.action { ctx, item, index, acc ->
            acc + item
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        // Should fall back to sequential and work correctly
        assertEquals(15, result)
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Test
    void testErrorStopsExecution() {
        def processed = []
        
        def loop = TaskFactory.createLoopTask("loop1", "Error Loop", ctx)
        loop.over { ctx -> [1, 2, 3, 4, 5] }
        loop.action { ctx, item, index ->
            processed << item
            if (item == 3) {
                throw new RuntimeException("Simulated error")
            }
            item
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(Exception) {
            promise.get()
        }
        
        // Should have processed 1, 2, 3 before error
        assertEquals([1, 2, 3], processed)
    }

    @Test
    void testContinueOnError() {
        def processed = []
        
        def loop = TaskFactory.createLoopTask("loop1", "Continue Loop", ctx)
        loop.over { ctx -> [1, 2, 3, 4, 5] }
        loop.continueOnError(true)
        loop.action { ctx, item, index ->
            processed << item
            if (item == 3) {
                throw new RuntimeException("Simulated error")
            }
            item
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        // Should process all items despite error
        assertEquals([1, 2, 3, 4, 5], processed)
        // Result should have nulls for failed items
        assertEquals(5, result.size())
    }

    @Test
    void testItemErrorHandler() {
        def errors = []
        
        def loop = TaskFactory.createLoopTask("loop1", "Error Handler Loop", ctx)
        loop.over { ctx -> [1, 2, 3, 4, 5] }
        loop.onItemError { ctx, item, index, error ->
            errors << [item: item, index: index]
            return "recovered-${item}".toString()
        }
        loop.action { ctx, item, index ->
            if (item == 3) {
                throw new RuntimeException("Error on 3")
            }
            item
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals(1, errors.size())
        assertEquals(3, errors[0].item)
        
        // Result should have recovery value
        assertEquals(5, result.size())
        assertEquals("recovered-3", result[2])
    }

    // =========================================================================
    // Result Mode Tests
    // =========================================================================

    @Test
    void testResultModeCount() {
        def loop = TaskFactory.createLoopTask("loop1", "Count Loop", ctx)
        loop.over { ctx -> [1, 2, 3, 4, 5] }
        loop.returns(LoopTask.ResultMode.COUNT)
        loop.action { ctx, item, index ->
            item * 2
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals(5, result)
    }

    // =========================================================================
    // Integration with TaskGraph Tests
    // =========================================================================

    @Test
    void testLoopInTaskGraph() {
        def graph = TaskGraph.build {
            serviceTask("prepare") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        ctx.globals.items = [10, 20, 30, 40, 50]
                        "prepared"
                    }
                }
            }
            
            loop("process") {
                over { ctx -> ctx.globals.items }
                accumulator(0)
                action { ctx, item, index, acc ->
                    acc + item
                }
            }
            
            serviceTask("finalize") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        "Total: ${prev}".toString()
                    }
                }
            }
            
            chainVia("prepare", "process", "finalize")
        }
        
        def result = graph.run().get()
        
        assertEquals("Total: 150", result)
    }

    @Test
    void testLoopWithDataTransform() {
        def graph = TaskGraph.build {
            serviceTask("fetch") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [
                            [id: 1, value: 10],
                            [id: 2, value: 20],
                            [id: 3, value: 30]
                        ]
                    }
                }
            }
            
            loop("enrich") {
                over { ctx -> ctx.globals.data }
                action { ctx, item, index ->
                    [id: item.id, value: item.value, enriched: true, index: index]
                }
            }
            
            task("aggregate", TaskType.DATA_TRANSFORM) {
                reduce([sum: 0, count: 0]) { acc, item ->
                    acc.sum += item.value
                    acc.count++
                    acc
                }
            }
            
            dependsOn("enrich", "fetch")
            dependsOn("aggregate", "enrich")
        }
        
        // Set data in context
        graph.ctx.globals.data = [
            [id: 1, value: 10],
            [id: 2, value: 20],
            [id: 3, value: 30]
        ]
        
        def result = graph.run().get()
        
        assertEquals(60, result.sum)
        assertEquals(3, result.count)
    }

    // =========================================================================
    // Validation Tests
    // =========================================================================

    @Test
    void testMissingCollectionProvider() {
        def loop = TaskFactory.createLoopTask("loop1", "Invalid Loop", ctx)
        loop.action { ctx, item, index -> item }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(Exception) {
            promise.get()
        }
    }

    @Test
    void testMissingAction() {
        def loop = TaskFactory.createLoopTask("loop1", "Invalid Loop", ctx)
        loop.over { ctx -> [1, 2, 3] }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(Exception) {
            promise.get()
        }
    }

    // =========================================================================
    // Complex Scenarios
    // =========================================================================

    @Test
    void testNestedDataProcessing() {
        def graph = TaskGraph.build {
            serviceTask("generate") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        ctx.globals.batches = [
                            [name: "batch1", items: [1, 2, 3]],
                            [name: "batch2", items: [4, 5, 6]],
                            [name: "batch3", items: [7, 8, 9]]
                        ]
                        "generated"
                    }
                }
            }
            
            loop("process-batches") {
                over { ctx -> ctx.globals.batches }
                accumulator([])
                action { ctx, batch, index, results ->
                    // Process each batch
                    def sum = batch.items.sum()
                    results << [batch: batch.name, sum: sum]
                    results
                }
            }
            
            chainVia("generate", "process-batches")
        }
        
        def result = graph.run().get()
        
        assertEquals(3, result.size())
        assertEquals(6, result[0].sum)   // 1+2+3
        assertEquals(15, result[1].sum)  // 4+5+6
        assertEquals(24, result[2].sum)  // 7+8+9
    }

    @Test
    void testRealWorldBatchProcessing() {
        def processed = []
        
        def loop = TaskFactory.createLoopTask("batch-process", "Batch Processor", ctx)
        loop.over { ctx ->
            // Simulate fetching batch items
            (1..100).collect { [id: it, data: "item-${it}"] }
        }
        loop.parallel(10)
        loop.action { ctx, item, index ->
            // Simulate processing
            processed << item.id
            ctx.promiseFactory.executeAsync {
                [id: item.id, processed: true]
            }
        }
        
        def promise = loop.execute(ctx.promiseFactory.createPromise(null))
        def result = promise.get()
        
        assertEquals(100, result.size())
        assertEquals(100, processed.size())
        // Filter out nulls before checking processed
        assertTrue(result.findAll { it != null }.every { it.processed })
    }
}
