package org.softwood.dag.task.gateways

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.softwood.dag.TaskGraph

import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests for AggregatorTask - Combining Parallel Task Results
 */
class AggregatorTaskTest {

    private TaskContext ctx

    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    private static <T> T awaitPromise(Promise<T> p) {
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until({ p.isDone() })
        return p.get()
    }

    @Test
    void testBasicAggregation() {
        def graph = TaskGraph.build {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { [count: 10] }
                }
            }
            
            serviceTask("task2") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { [count: 20] }
                }
            }
            
            serviceTask("task3") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { [count: 30] }
                }
            }
            
            task("aggregate", TaskType.AGGREGATOR) {
                waitFor "task1", "task2", "task3"
                strategy { results ->
                    [total: results.sum { it.count }]
                }
            }
            
            // Wire parallel execution
            chainVia("task1", "aggregate")
            chainVia("task2", "aggregate")
            chainVia("task3", "aggregate")
        }
        
        def result = awaitPromise(graph.run())
        
        assertNotNull(result)
        assertEquals(60, result.total)
    }

    @Test
    void testAggregationWithTimeout() {
        def graph = TaskGraph.build {
            serviceTask("fast1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        Thread.sleep(100)
                        [value: 1]
                    }
                }
            }
            
            serviceTask("fast2") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        Thread.sleep(100)
                        [value: 2]
                    }
                }
            }
            
            serviceTask("slow3") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        Thread.sleep(5000)
                        [value: 3]
                    }
                }
            }
            
            task("aggregate", TaskType.AGGREGATOR) {
                waitFor "fast1", "fast2", "slow3"
                
                // FIXED: Correct timeout syntax - Duration first, then Map
                timeout(Duration.ofMillis(500), [onTimeout: { partialResults ->
                    [
                        status: "timeout",
                        count: partialResults.size(),
                        sum: partialResults.sum { it.value }
                    ]
                }])
                
                strategy { results ->
                    [
                        status: "complete",
                        count: results.size(),
                        sum: results.sum { it.value }
                    ]
                }
            }
            
            // Wire parallel execution
            chainVia("fast1", "aggregate")
            chainVia("fast2", "aggregate")
            chainVia("slow3", "aggregate")
        }
        
        def result = awaitPromise(graph.run())
        
        assertNotNull(result)
        assertEquals("timeout", result.status)
        assertEquals(2, result.count)
        assertEquals(3, result.sum)
    }

    @Test
    void testCompletionSize() {
        def graph = TaskGraph.build {
            serviceTask("api1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        Thread.sleep(100)
                        [data: "from-api1"]
                    }
                }
            }
            
            serviceTask("api2") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        Thread.sleep(200)
                        [data: "from-api2"]
                    }
                }
            }
            
            serviceTask("api3") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        Thread.sleep(300)
                        [data: "from-api3"]
                    }
                }
            }
            
            task("first-two", TaskType.AGGREGATOR) {
                waitFor "api1", "api2", "api3"
                completionSize 2
                
                strategy { results ->
                    [
                        count: results.size(),
                        first: results[0].data
                    ]
                }
            }
            
            // Wire parallel execution
            chainVia("api1", "first-two")
            chainVia("api2", "first-two")
            chainVia("api3", "first-two")
        }
        
        def result = awaitPromise(graph.run())
        
        assertNotNull(result)
        assertEquals(2, result.count)
        assertEquals("from-api1", result.first)
    }

    @Test
    void testAggregationWithListResults() {
        def graph = TaskGraph.build {
            serviceTask("batch1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [items: [1, 2, 3]]
                    }
                }
            }
            
            serviceTask("batch2") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [items: [4, 5, 6]]
                    }
                }
            }
            
            task("combine", TaskType.AGGREGATOR) {
                waitFor "batch1", "batch2"
                strategy { results ->
                    [
                        allItems: results.collectMany { it.items },
                        total: results.sum { it.items.size() }
                    ]
                }
            }
            
            // Wire parallel execution
            chainVia("batch1", "combine")
            chainVia("batch2", "combine")
        }
        
        def result = awaitPromise(graph.run())
        
        assertNotNull(result)
        assertEquals([1, 2, 3, 4, 5, 6], result.allItems)
        assertEquals(6, result.total)
    }

    @Test
    void testEmptySourceTasks() {
        def aggregator = new AggregatorTask("agg", "Aggregator", ctx)
        aggregator.strategy { results -> results }
        
        // FIXED: Need to await the promise to catch the exception
        def promise = aggregator.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(Exception) {
            awaitPromise(promise)
        }
    }

    @Test
    void testMissingAggregationStrategy() {
        def aggregator = new AggregatorTask("agg", "Aggregator", ctx)
        aggregator.waitFor("task1", "task2")
        
        // FIXED: Need to await the promise to catch the exception
        def promise = aggregator.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(Exception) {
            awaitPromise(promise)
        }
    }

    @Test
    void testInvalidCompletionSize() {
        def aggregator = new AggregatorTask("agg", "Aggregator", ctx)
        aggregator.waitFor("task1", "task2")
        aggregator.completionSize(5)
        aggregator.strategy { results -> results }
        
        // FIXED: Need to await the promise to catch the exception
        def promise = aggregator.execute(ctx.promiseFactory.createPromise(null))
        
        assertThrows(Exception) {
            awaitPromise(promise)
        }
    }

    @Test
    void testAggregationWithComplexData() {
        def graph = TaskGraph.build {
            serviceTask("user-service") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [
                            users: [
                                [id: 1, name: "Alice"],
                                [id: 2, name: "Bob"]
                            ]
                        ]
                    }
                }
            }
            
            serviceTask("order-service") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [
                            orders: [
                                [id: 101, userId: 1, total: 100],
                                [id: 102, userId: 2, total: 200]
                            ]
                        ]
                    }
                }
            }
            
            task("combine-data", TaskType.AGGREGATOR) {
                waitFor "user-service", "order-service"
                strategy { results ->
                    def users = results.find { it.users }?.users ?: []
                    def orders = results.find { it.orders }?.orders ?: []
                    
                    [
                        userCount: users.size(),
                        orderCount: orders.size(),
                        totalRevenue: orders.sum { it.total }
                    ]
                }
            }
            
            // Wire parallel execution
            chainVia("user-service", "combine-data")
            chainVia("order-service", "combine-data")
        }
        
        def result = awaitPromise(graph.run())
        
        assertNotNull(result)
        assertEquals(2, result.userCount)
        assertEquals(2, result.orderCount)
        assertEquals(300, result.totalRevenue)
    }
}
