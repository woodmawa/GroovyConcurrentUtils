package org.softwood.dag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.awaitility.Awaitility
import java.util.concurrent.TimeUnit
import org.softwood.promise.Promise
import org.softwood.dag.task.*

/**
 * Tests for SubGraphTask (reusable workflow templates)
 */
class SubGraphTaskTest {

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
    void testInlineSubGraph() {
        def task = new SubGraphTask("sub", "Sub", ctx)
        
        task.subGraph {
            serviceTask("step1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { "Result1" }
                }
            }
            
            serviceTask("step2") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { prev + "-Result2" }
                }
            }
            
            fork("flow") {
                from "step1"
                to "step2"
            }
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals("Result1-Result2", result)
    }

    @Test
    void testSubGraphWithInputMapper() {
        def task = new SubGraphTask("sub", "Sub", ctx)
        
        task.inputMapper { prev ->
            [value: prev * 2]
        }
        
        task.subGraph {
            serviceTask("process") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        prev.value + 10
                    }
                }
            }
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise(5))
        def result = awaitPromise(promise)
        
        assertEquals(20, result) // (5 * 2) + 10
    }

    @Test
    void testSubGraphWithOutputExtractor() {
        def task = new SubGraphTask("sub", "Sub", ctx)
        
        task.subGraph {
            serviceTask("process") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [id: 123, name: "Test", extra: "data"]
                    }
                }
            }
        }
        
        task.outputExtractor { result ->
            [id: result.id, name: result.name]
        }
        
        def promise = task.execute(ctx.promiseFactory.createPromise(null))
        def result = awaitPromise(promise)
        
        assertEquals(123, result.id)
        assertEquals("Test", result.name)
        assertFalse(result.containsKey("extra"))
    }

    @Test
    void testSubGraphInWorkflow() {
        def results = [:]
        
        def graph = TaskGraph.build {
            serviceTask("prepare") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { 10 }
                }
            }
            
            task("sub-process", TaskType.SUBGRAPH) {
                subGraph {
                    serviceTask("double") {
                        action { ctx, prev ->
                            ctx.promiseFactory.executeAsync { prev * 2 }
                        }
                    }
                    
                    serviceTask("add-five") {
                        action { ctx, prev ->
                            ctx.promiseFactory.executeAsync { prev + 5 }
                        }
                    }
                    
                    fork("flow") {
                        from "double"
                        to "add-five"
                    }
                }
            }
            
            serviceTask("finalize") {
                action { ctx, prev ->
                    results.final = prev
                    ctx.promiseFactory.executeAsync { "Done: $prev" }
                }
            }
            
            fork("main") {
                from "prepare"
                to "sub-process"
            }
            
            fork("after-sub") {
                from "sub-process"
                to "finalize"
            }
        }
        
        def promise = graph.run()
        awaitPromise(promise)
        
        assertEquals(25, results.final) // (10 * 2) + 5
    }

    @Test
    void testTemplateReuse() {
        // Create a template
        def emailTemplate = TaskGraph.build {
            serviceTask("send-email") {
                action { ctx, prev ->
                    def params = prev
                    ctx.promiseFactory.executeAsync {
                        [sent: true, to: params.to, subject: params.subject]
                    }
                }
            }
        }
        
        // Use template in workflow
        def graph = TaskGraph.build {
            serviceTask("prepare-email1") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync {
                        [to: "user1@test.com", subject: "Welcome"]
                    }
                }
            }
            
            task("send1", TaskType.SUBGRAPH) {
                template emailTemplate
            }
            
            fork("flow") {
                from "prepare-email1"
                to "send1"
            }
        }
        
        def promise = graph.run()
        def result = awaitPromise(promise)
        
        assertTrue(result.sent)
        assertEquals("user1@test.com", result.to)
    }

    @Test
    void testNestedSubGraphs() {
        def results = [:]
        
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, _ ->
                    ctx.promiseFactory.executeAsync { 5 }
                }
            }
            
            task("outer-sub", TaskType.SUBGRAPH) {
                subGraph {
                    serviceTask("outer-step1") {
                        action { ctx, prev ->
                            ctx.promiseFactory.executeAsync { prev * 2 }
                        }
                    }
                    
                    task("inner-sub", TaskType.SUBGRAPH) {
                        subGraph {
                            serviceTask("inner-step1") {
                                action { ctx, prev ->
                                    ctx.promiseFactory.executeAsync { prev + 10 }
                                }
                            }
                        }
                    }
                    
                    fork("outer-flow") {
                        from "outer-step1"
                        to "inner-sub"
                    }
                }
            }
            
            serviceTask("capture") {
                action { ctx, prev ->
                    results.captured = prev
                    ctx.promiseFactory.executeAsync { prev }
                }
            }
            
            fork("main") {
                from "start"
                to "outer-sub"
            }
            
            fork("after") {
                from "outer-sub"
                to "capture"
            }
        }
        
        def promise = graph.run()
        awaitPromise(promise)
        
        assertEquals(20, results.captured) // ((5 * 2) + 10)
    }
}
