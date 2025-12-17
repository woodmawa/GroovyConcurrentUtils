package org.softwood.actor.hierarchy

import org.junit.jupiter.api.*
import org.softwood.actor.Actor
import org.softwood.actor.ActorSystem
import org.softwood.actor.lifecycle.Terminated
import org.softwood.actor.supervision.SupervisionStrategy
import org.softwood.actor.supervision.SupervisorDirective

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static org.awaitility.Awaitility.*
import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for actor hierarchies and parent-child relationships.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ActorHierarchyTest {
    
    ActorSystem system
    
    @BeforeEach
    void setup() {
        system = new ActorSystem('test-hierarchy')
    }
    
    @AfterEach
    void cleanup() {
        system?.shutdown()
    }
    
    // ============================================================
    // Basic Hierarchy
    // ============================================================
    
    @Test
    @DisplayName("Parent can spawn children")
    void testSpawnChild() {
        // Given: A parent actor
        def children = new CopyOnWriteArrayList<String>()
        
        def parent = system.actor {
            name 'parent'
            onMessage { msg, ctx ->
                if (msg == 'spawn-child') {
                    def child = ctx.spawn('child-1') { msg2, ctx2 ->
                        // Child logic
                    }
                    children << child.name
                }
            }
        }
        
        // When: Spawn a child
        parent.tell('spawn-child')
        
        // Then: Child is created and tracked
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertEquals(1, children.size())
            assertTrue(system.hasActor('child-1'))
        }
        
        // Verify hierarchy
        String parentOfChild = system.hierarchy.getParent(system.getActor('child-1'))
        assertEquals('parent', parentOfChild)
    }
    
    @Test
    @DisplayName("Parent can spawn multiple children")
    void testSpawnMultipleChildren() {
        // Given: A parent
        def parent = system.actor {
            name 'parent'
            onMessage { msg, ctx ->
                if (msg == 'spawn-workers') {
                    (1..3).each { i ->
                        ctx.spawn("worker-$i") { msg2, ctx2 -> }
                    }
                }
            }
        }
        
        // When: Spawn multiple children
        parent.tell('spawn-workers')
        
        // Then: All children exist
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertTrue(system.hasActor('worker-1'))
            assertTrue(system.hasActor('worker-2'))
            assertTrue(system.hasActor('worker-3'))
        }
        
        // Verify hierarchy
        Set<String> children = system.hierarchy.getChildren(parent)
        assertEquals(3, children.size())
    }
    
    @Test
    @DisplayName("Can get parent and children from context")
    void testContextHierarchyMethods() {
        // Given: Parent with children
        def parentInfo = new CopyOnWriteArrayList<String>()
        def childInfo = new CopyOnWriteArrayList<String>()
        
        def parent = system.actor {
            name 'parent'
            onMessage { msg, ctx ->
                if (msg == 'spawn') {
                    ctx.spawn('child') { msg2, ctx2 ->
                        if (msg2 == 'get-parent') {
                            childInfo << ctx2.getParent()
                        }
                    }
                } else if (msg == 'get-children') {
                    ctx.getChildren().each { childInfo << it }
                }
            }
        }
        
        // When: Spawn and query
        parent.tell('spawn')
        Thread.sleep(100)
        parent.tell('get-children')
        system.getActor('child').tell('get-parent')
        
        // Then: Correct relationships
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertTrue(childInfo.contains('child'))
            assertTrue(childInfo.contains('parent'))
        }
    }
    
    // ============================================================
    // Supervision Integration
    // ============================================================
    
    @Test
    @DisplayName("ESCALATE directive sends error to parent")
    void testEscalateToParent() {
        // Given: Parent with escalating child
        def escalations = new CopyOnWriteArrayList<Map>()
        def latch = new CountDownLatch(1)
        
        def parent = system.actor {
            name 'supervisor'
            onMessage { msg, ctx ->
                if (msg == 'spawn-child') {
                    def child = ctx.spawn('failing-child') { msg2, ctx2 ->
                        if (msg2 == 'fail') {
                            throw new RuntimeException("Child error")
                        }
                    }
                    // Set child's strategy to ESCALATE
                    child.setSupervisionStrategy(SupervisionStrategy.escalateAlways())
                } else if (msg instanceof Map && msg.type == 'child-error') {
                    escalations << msg
                    latch.countDown()
                }
            }
        }
        
        // When: Child fails
        parent.tell('spawn-child')
        Thread.sleep(100)
        system.getActor('failing-child').tell('fail')
        
        // Then: Parent receives escalation
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, escalations.size())
        assertEquals('failing-child', escalations[0].childName)
    }
    
    @Test
    @DisplayName("Parent receives Terminated when child stops")
    void testParentWatchesChild() {
        // Given: Parent that spawns child
        def terminated = new CopyOnWriteArrayList<String>()
        def latch = new CountDownLatch(1)
        
        def parent = system.actor {
            name 'parent'
            onMessage { msg, ctx ->
                if (msg == 'spawn') {
                    ctx.spawn('child') { msg2, ctx2 ->
                        // Child just exists
                    }
                } else if (msg instanceof Terminated) {
                    terminated << msg.actor.name
                    latch.countDown()
                }
            }
        }
        
        // When: Child stops (from outside)
        parent.tell('spawn')
        Thread.sleep(100)
        def child = system.getActor('child')
        assertNotNull(child)
        child.stop()  // Stop from test thread, not from within handler
        
        // Then: Parent notified
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(['child'], terminated)
    }
    
    // ============================================================
    // Hierarchy Patterns
    // ============================================================
    
    @Test
    @DisplayName("Supervisor pattern with automatic child restart")
    void testSupervisorRestartChild() {
        // Given: Supervisor that restarts children
        def restarts = new AtomicInteger(0)
        def latch = new CountDownLatch(1)
        
        def supervisor = system.actor {
            name 'supervisor'
            state childCount: 0
            onMessage { msg, ctx ->
                if (msg == 'start') {
                    ctx.spawn('worker') { msg2, ctx2 ->
                        // Worker just exists
                    }
                } else if (msg instanceof Terminated) {
                    // Child died - restart it
                    restarts.incrementAndGet()
                    ctx.state.childCount++
                    ctx.spawn("worker-${ctx.state.childCount}") { msg2, ctx2 -> }
                    latch.countDown()
                }
            }
        }
        
        // When: Start and stop worker (from outside)
        supervisor.tell('start')
        Thread.sleep(100)
        def worker = system.getActor('worker')
        assertNotNull(worker)
        worker.stop()  // Stop from test thread
        
        // Then: Child restarted
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(restarts.get() >= 1)
        assertTrue(system.hasActor('worker-1'))
    }
    
    @Test
    @DisplayName("Multi-level hierarchy (grandchildren)")
    void testMultiLevelHierarchy() {
        // Given: Three-level hierarchy
        def parent = system.actor {
            name 'grandparent'
            onMessage { msg, ctx ->
                if (msg == 'spawn-family') {
                    def child = ctx.spawn('parent') { msg2, ctx2 ->
                        if (msg2 == 'spawn-child') {
                            ctx2.spawn('grandchild') { msg3, ctx3 -> }
                        }
                    }
                    child.tell('spawn-child')
                }
            }
        }
        
        // When: Create hierarchy
        parent.tell('spawn-family')
        
        // Then: All exist
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertTrue(system.hasActor('parent'))
            assertTrue(system.hasActor('grandchild'))
        }
        
        // Verify relationships
        assertEquals('grandparent', system.hierarchy.getParent(system.getActor('parent')))
        assertEquals('parent', system.hierarchy.getParent(system.getActor('grandchild')))
    }
    
    // ============================================================
    // Edge Cases
    // ============================================================
    
    @Test
    @DisplayName("Cannot create circular hierarchy")
    void testPreventCircularHierarchy() {
        // Given: Two actors
        def actor1 = system.actor {
            name 'actor1'
            onMessage { msg, ctx -> }
        }
        
        def actor2 = system.actor {
            name 'actor2'
            onMessage { msg, ctx -> }
        }
        
        // When: Try to create circular relationship
        system.hierarchy.registerChild(actor1, actor2)
        
        // Then: Cannot make actor1 child of actor2
        assertThrows(IllegalArgumentException.class) {
            system.hierarchy.registerChild(actor2, actor1)
        }
    }
    
    @Test
    @DisplayName("Hierarchy cleanup when parent stops")
    void testHierarchyCleanup() {
        // Given: Parent with child
        def parent = system.actor {
            name 'parent'
            onMessage { msg, ctx ->
                if (msg == 'spawn') {
                    ctx.spawn('child') { msg2, ctx2 -> }
                }
            }
        }
        
        parent.tell('spawn')
        await().atMost(500, TimeUnit.MILLISECONDS).until {
            system.hasActor('child')
        }
        
        def child = system.getActor('child')
        assertNotNull(child)
        
        // Verify parent-child relationship exists
        assertEquals('parent', system.hierarchy.getParent(child))
        
        // When: Parent stops
        parent.stop()
        Thread.sleep(200)
        
        // Then: Hierarchy cleaned up - child is orphaned
        assertNull(system.hierarchy.getParent(child), "Child should be orphaned after parent stops")
    }
}
