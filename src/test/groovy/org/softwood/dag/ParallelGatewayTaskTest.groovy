package org.softwood.dag.task

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import static org.junit.jupiter.api.Assertions.*

import org.softwood.dag.TaskGraph

/**
 * Tests for ParallelGatewayTask - AND-split parallel execution
 */
class ParallelGatewayTaskTest {

    TaskContext ctx
    
    @BeforeEach
    void setup() {
        ctx = new TaskContext()
    }

    // =========================================================================
    // Basic Tests
    // =========================================================================

    @Test
    void testParallelGatewayRouting() {
        def gateway = TaskFactory.createParallelGateway("pg1", "Parallel Gateway", ctx)
        gateway.branches("branch1", "branch2", "branch3")
        
        // Verify routing returns all branches
        def targets = gateway.route(null)
        
        assertEquals(3, targets.size())
        assertTrue(targets.contains("branch1"))
        assertTrue(targets.contains("branch2"))
        assertTrue(targets.contains("branch3"))
    }

    @Test
    void testEmptyBranches() {
        def gateway = TaskFactory.createParallelGateway("pg1", "Empty Gateway", ctx)
        
        def targets = gateway.route(null)
        
        assertEquals(0, targets.size())
    }

    // =========================================================================
    // DSL Tests
    // =========================================================================

    @Test
    void testDslBranchesMethod() {
        def gateway = TaskFactory.createParallelGateway("pg1", "Gateway", ctx)
        
        gateway.branches("a", "b", "c")
        
        assertEquals(3, gateway.branches.size())
        assertEquals(["a", "b", "c"], gateway.branches)
    }

    @Test
    void testDslTargetsMethod() {
        def gateway = TaskFactory.createParallelGateway("pg1", "Gateway", ctx)
        
        gateway.targets("x", "y", "z")
        
        assertEquals(3, gateway.branches.size())
        assertEquals(["x", "y", "z"], gateway.branches)
    }

    // =========================================================================
    // Integration Tests
    // =========================================================================

    @Test
    void testParallelGatewayInGraph() {
        def executed = []
        
        def graph = TaskGraph.build {
            serviceTask("prepare") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        executed << "prepare"
                        "data"
                    }
                }
            }
            
            parallelGateway("split") {
                branches "process1", "process2"
            }
            
            serviceTask("process1") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        executed << "process1"
                        "p1-done"
                    }
                }
            }
            
            serviceTask("process2") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        executed << "process2"
                        "p2-done"
                    }
                }
            }
            
            serviceTask("finalize") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        executed << "finalize"
                        "complete"
                    }
                }
            }
            
            chainVia("prepare", "split")
            dependsOn("process1", "split")
            dependsOn("process2", "split")
            dependsOn("finalize", "process1", "process2")
        }
        
        def result = graph.run().get()
        
        // Verify execution order
        assertTrue(executed.contains("prepare"))
        assertTrue(executed.contains("process1"))
        assertTrue(executed.contains("process2"))
        assertTrue(executed.contains("finalize"))
        
        // prepare must come before parallel tasks
        def prepareIdx = executed.indexOf("prepare")
        def p1Idx = executed.indexOf("process1")
        def p2Idx = executed.indexOf("process2")
        def finalizeIdx = executed.indexOf("finalize")
        
        assertTrue(prepareIdx < p1Idx)
        assertTrue(prepareIdx < p2Idx)
        assertTrue(p1Idx < finalizeIdx)
        assertTrue(p2Idx < finalizeIdx)
    }

    @Test
    void testMultipleBranches() {
        def results = []
        
        def graph = TaskGraph.build {
            serviceTask("start") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { 1 }
                }
            }
            
            parallel("gateway") {
                branches "a", "b", "c", "d", "e"
            }
            
            // Create 5 parallel tasks
            ['a', 'b', 'c', 'd', 'e'].each { taskId ->
                serviceTask(taskId) {
                    action { ctx, prev ->
                        ctx.promiseFactory.executeAsync {
                            results << taskId
                            "result-${taskId}".toString()
                        }
                    }
                }
            }
            
            chainVia("start", "gateway")
            dependsOn("a", "gateway")
            dependsOn("b", "gateway")
            dependsOn("c", "gateway")
            dependsOn("d", "gateway")
            dependsOn("e", "gateway")
        }
        
        graph.run().get()
        
        // All branches should have executed
        assertEquals(5, results.size())
        assertTrue(results.contains("a"))
        assertTrue(results.contains("b"))
        assertTrue(results.contains("c"))
        assertTrue(results.contains("d"))
        assertTrue(results.contains("e"))
    }

    // =========================================================================
    // Factory and Type Tests
    // =========================================================================

    @Test
    void testFactoryCreation() {
        def gateway = TaskFactory.createParallelGateway("pg1", "Test Gateway", ctx)
        
        assertNotNull(gateway)
        assertEquals("pg1", gateway.id)
        assertEquals("Test Gateway", gateway.name)
        assertTrue(gateway instanceof ParallelGatewayTask)
    }

    @Test
    void testTaskTypeEnum() {
        def gateway = TaskFactory.createTask(TaskType.PARALLEL_GATEWAY, "pg1", "Gateway", ctx)
        
        assertNotNull(gateway)
        assertTrue(gateway instanceof ParallelGatewayTask)
    }

    @Test
    void testFriendlyNames() {
        def names = ["parallel", "parallelgateway", "and", "andgateway", "fanout"]
        
        names.each { name ->
            def type = TaskType.fromString(name)
            assertEquals(TaskType.PARALLEL_GATEWAY, type)
        }
    }
}
