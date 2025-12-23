package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.softwood.dag.task.*
import java.time.Duration

/**
 * Tests for TasksCollection static builder and promise chaining
 */
class TasksCollectionDslTest {

    // =========================================================================
    // Static Builder Tests
    // =========================================================================

    @Test
    void testStaticBuilder() {
        def tasks = TasksCollection.tasks {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "result1" }
                }
            }
            
            serviceTask("task2") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "result2" }
                }
            }
        }
        
        assertNotNull(tasks)
        assertEquals(2, tasks.taskCount)
        assertNotNull(tasks.find("task1"))
        assertNotNull(tasks.find("task2"))
    }

    @Test
    void testStaticBuilderWithDifferentTaskTypes() {
        def tasks = TasksCollection.tasks {
            serviceTask("service") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "service" }
                }
            }
            
            task("transform", TaskType.DATA_TRANSFORM) {
                transform { data -> data.toUpperCase() }
            }
            
            timer("timer") {
                interval Duration.ofSeconds(1)
                maxExecutions 1
                action { ctx -> "timer" }
            }
        }
        
        assertEquals(3, tasks.taskCount)
        assertTrue(tasks.find("service") instanceof ServiceTask)
        assertTrue(tasks.find("transform") instanceof DataTransformTask)
        assertTrue(tasks.find("timer") instanceof TimerTask)
    }

    // =========================================================================
    // Chain API Tests
    // =========================================================================

    @Test
    void testSimpleChain() {
        def tasks = TasksCollection.tasks {
            serviceTask("step1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { 10 }
                }
            }
            
            serviceTask("step2") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { prev * 2 }
                }
            }
            
            serviceTask("step3") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { prev + 5 }
                }
            }
        }
        
        def promise = tasks.chain("step1", "step2", "step3").run()
        def result = promise.get()
        
        // (10 * 2) + 5 = 25
        assertEquals(25, result)
    }

    @Test
    void testChainWithInitialValue() {
        def tasks = TasksCollection.tasks {
            serviceTask("double") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { prev * 2 }
                }
            }
            
            serviceTask("add10") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { prev + 10 }
                }
            }
        }
        
        def promise = tasks.chain("double", "add10").run(5)
        def result = promise.get()
        
        // (5 * 2) + 10 = 20
        assertEquals(20, result)
    }

    @Test
    void testChainWithOnComplete() {
        def completedValue = null
        
        def tasks = TasksCollection.tasks {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "hello" }
                }
            }
            
            serviceTask("task2") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { prev.toUpperCase() }
                }
            }
        }
        
        def promise = tasks.chain("task1", "task2")
            .onComplete { result ->
                completedValue = result
            }
            .run()
        
        // Wait for promise to complete
        def finalResult = promise.get()
        
        // Check both the return value and the handler variable
        assertEquals("HELLO", finalResult)
        assertEquals("HELLO", completedValue)
    }

    @Test
    void testChainWithOnError() {
        def caughtError = null
        
        def tasks = TasksCollection.tasks {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "ok" }
                }
            }
            
            serviceTask("failing") {
                action { ctx, prev ->
                    throw new RuntimeException("Expected failure")
                }
            }
        }
        
        def promise = tasks.chain("task1", "failing")
            .onError { error ->
                caughtError = error
            }
            .run()
        
        assertThrows(Exception) {
            promise.get()
        }
        
        assertNotNull(caughtError)
        // The error gets wrapped in "exceeded retry attempts"
        assertTrue(caughtError.message.contains("exceeded retry attempts") || 
                   caughtError.message.contains("Expected failure"))
    }

    @Test
    void testChainWithDataTransform() {
        def tasks = TasksCollection.tasks {
            serviceTask("fetch") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [[id: 1, value: 10], [id: 2, value: 20], [id: 3, value: 30]]
                    }
                }
            }
            
            task("transform", TaskType.DATA_TRANSFORM) {
                // Map: double each value
                map { item -> [id: item.id, value: item.value * 2] }
                // Filter: keep items where doubled value > 20 (so original > 10)
                // This filters the COLLECTION, returning [[id:2, value:40], [id:3, value:60]]
                transform { items ->
                    items.findAll { it.value > 20 }
                }
            }
            
            serviceTask("count") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { prev.size() }
                }
            }
        }
        
        def promise = tasks.chain("fetch", "transform", "count").run()
        def result = promise.get()
        
        // 2 items have doubled value > 20: [id:2, value:40] and [id:3, value:60]
        assertEquals(2, result)
    }

    // =========================================================================
    // Complex Pipeline Tests
    // =========================================================================

    @Test
    void testMultiStepDataPipeline() {
        def tasks = TasksCollection.tasks {
            serviceTask("fetch-users") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [
                            [name: "Alice", age: 30, dept: "Engineering"],
                            [name: "Bob", age: 25, dept: "Sales"],
                            [name: "Charlie", age: 35, dept: "Engineering"]
                        ]
                    }
                }
            }
            
            task("filter-engineers", TaskType.DATA_TRANSFORM) {
                transform { users ->
                    users.findAll { it.dept == "Engineering" }
                }
            }
            
            task("calculate-avg-age", TaskType.DATA_TRANSFORM) {
                reduce([count: 0, totalAge: 0]) { acc, user ->
                    acc.count++
                    acc.totalAge += user.age
                    acc
                }
                
                transform { stats ->
                    stats.avgAge = stats.totalAge / stats.count
                    stats
                }
            }
            
            serviceTask("format-result") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        "Average age: ${prev.avgAge} (${prev.count} engineers)"
                    }
                }
            }
        }
        
        def result = tasks.chain("fetch-users", "filter-engineers", "calculate-avg-age", "format-result")
            .run()
            .get()
        
        assertTrue(result.contains("32.5"))
        assertTrue(result.contains("2 engineers"))
    }

    @Test
    void testChainableReusableTasks() {
        def tasks = TasksCollection.tasks {
            serviceTask("double") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { prev * 2 }
                }
            }
            
            serviceTask("add10") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { prev + 10 }
                }
            }
        }
        
        // Run first chain
        def result1 = tasks.chain("double", "add10").run(5).get()
        assertEquals(20, result1)  // (5 * 2) + 10
        
        // Reuse tasks in different chain
        def result2 = tasks.chain("add10", "double").run(5).get()
        assertEquals(30, result2)  // (5 + 10) * 2
        
        // Repeat operations
        def result3 = tasks.chain("double", "double").run(5).get()
        assertEquals(20, result3)  // 5 * 2 * 2
    }

    // =========================================================================
    // Error Cases
    // =========================================================================

    @Test
    void testChainWithUnknownTask() {
        def tasks = TasksCollection.tasks {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "ok" }
                }
            }
        }
        
        def promise = tasks.chain("task1", "unknown-task").run()
        
        def exception = assertThrows(Exception) {
            promise.get()
        }
        
        assertTrue(exception.message.contains("Unknown task"))
    }

    @Test
    void testEmptyChain() {
        def tasks = TasksCollection.tasks {
            serviceTask("task1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "ok" }
                }
            }
        }
        
        assertThrows(IllegalStateException) {
            tasks.chain().run()
        }
    }

    // =========================================================================
    // Real-World Example
    // =========================================================================

    @Test
    void testWebScrapingPipeline() {
        def tasks = TasksCollection.tasks {
            serviceTask("fetch-html") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        "<html><body><div class='price'>29.99</div><div class='price'>49.99</div></body></html>"
                    }
                }
            }
            
            task("extract-prices", TaskType.DATA_TRANSFORM) {
                transform { html ->
                    // Simple regex extraction (in reality use proper parser)
                    def matcher = html =~ /<div class='price'>([0-9.]+)<\\/div>/
                    matcher.collect { it[1] as BigDecimal }
                }
            }
            
            task("analyze-prices", TaskType.DATA_TRANSFORM) {
                reduce([min: null, max: null, avg: 0]) { acc, price ->
                    acc.min = acc.min == null ? price : [acc.min, price].min()
                    acc.max = acc.max == null ? price : [acc.max, price].max()
                    acc
                }
                
                transform { stats ->
                    stats.avg = (stats.min + stats.max) / 2
                    stats
                }
            }
            
            serviceTask("format-report") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        """
                        Price Analysis:
                        - Min: \$${prev.min}
                        - Max: \$${prev.max}
                        - Avg: \$${prev.avg}
                        """.trim()
                    }
                }
            }
        }
        
        def report = tasks.chain("fetch-html", "extract-prices", "analyze-prices", "format-report")
            .run()
            .get()
        
        assertTrue(report.contains("29.99"))
        assertTrue(report.contains("49.99"))
        assertTrue(report.contains("39.99"))  // Average
    }
}
