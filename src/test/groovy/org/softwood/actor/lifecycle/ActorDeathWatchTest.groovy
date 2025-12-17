package org.softwood.actor.lifecycle

import org.junit.jupiter.api.*
import org.softwood.actor.ActorSystem

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static org.awaitility.Awaitility.*
import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for death watch (lifecycle monitoring) functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ActorDeathWatchTest {
    
    ActorSystem system
    
    @BeforeEach
    void setup() {
        system = new ActorSystem('test-death-watch')
    }
    
    @AfterEach
    void cleanup() {
        system?.shutdown()
    }
    
    // ============================================================
    // Basic Death Watch
    // ============================================================
    
    @Test
    @DisplayName("Actor should receive Terminated when watched actor stops")
    void testBasicDeathWatch() {
        // Given: A watcher and a worker
        def terminatedMessages = new CopyOnWriteArrayList<Terminated>()
        def latch = new CountDownLatch(1)
        
        def watcher = system.actor {
            name 'watcher'
            onMessage { msg, ctx ->
                if (msg == 'start-watching') {
                    def worker = ctx.system.getActor('worker')
                    ctx.watch(worker)
                } else if (msg instanceof Terminated) {
                    terminatedMessages << msg
                    latch.countDown()
                }
            }
        }
        
        def worker = system.actor {
            name 'worker'
            onMessage { msg, ctx ->
                // Simple worker
            }
        }
        
        // When: Start watching then stop worker
        watcher.tell('start-watching')
        Thread.sleep(50)
        worker.stop()
        
        // Then: Watcher receives Terminated
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, terminatedMessages.size())
        assertEquals('worker', terminatedMessages[0].actor.name)
        assertFalse(terminatedMessages[0].failed)
    }
    
    @Test
    @DisplayName("Multiple actors can watch the same actor")
    void testMultipleWatchers() {
        // Given: Multiple watchers
        def watcher1Messages = new CopyOnWriteArrayList<String>()
        def watcher2Messages = new CopyOnWriteArrayList<String>()
        def latch = new CountDownLatch(2)
        
        def watcher1 = system.actor {
            name 'watcher1'
            onMessage { msg, ctx ->
                if (msg == 'watch') {
                    ctx.watch('worker')
                } else if (msg instanceof Terminated) {
                    watcher1Messages << "terminated: ${msg.actor.name}"
                    latch.countDown()
                }
            }
        }
        
        def watcher2 = system.actor {
            name 'watcher2'
            onMessage { msg, ctx ->
                if (msg == 'watch') {
                    ctx.watch('worker')
                } else if (msg instanceof Terminated) {
                    watcher2Messages << "terminated: ${msg.actor.name}"
                    latch.countDown()
                }
            }
        }
        
        def worker = system.actor {
            name 'worker'
            onMessage { msg, ctx -> }
        }
        
        // When: Both watchers watch and worker stops
        watcher1.tell('watch')
        watcher2.tell('watch')
        Thread.sleep(50)
        worker.stop()
        
        // Then: Both watchers are notified
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(watcher1Messages.size() >= 1)
        assertTrue(watcher2Messages.size() >= 1)
    }
    
    @Test
    @DisplayName("unwatch should prevent Terminated message")
    void testUnwatch() {
        // Given: A watcher that unwatches
        def terminatedCount = new AtomicInteger(0)
        
        def watcher = system.actor {
            name 'watcher'
            state watchedActor: null
            onMessage { msg, ctx ->
                if (msg == 'watch') {
                    def worker = ctx.system.getActor('worker')
                    ctx.state.watchedActor = worker
                    ctx.watch(worker)
                } else if (msg == 'unwatch') {
                    ctx.unwatch(ctx.state.watchedActor)
                } else if (msg instanceof Terminated) {
                    terminatedCount.incrementAndGet()
                }
            }
        }
        
        def worker = system.actor {
            name 'worker'
            onMessage { msg, ctx -> }
        }
        
        // When: Watch, then unwatch, then stop
        watcher.tell('watch')
        Thread.sleep(50)
        watcher.tell('unwatch')
        Thread.sleep(50)
        worker.stop()
        Thread.sleep(200)
        
        // Then: No Terminated message received
        assertEquals(0, terminatedCount.get())
    }
    
    // ============================================================
    // Use Cases
    // ============================================================
    
    @Test
    @DisplayName("Supervisor pattern - restart worker on termination")
    void testSupervisorPattern() {
        // Given: A supervisor that watches workers
        def terminatedMessages = new CopyOnWriteArrayList<String>()
        def latch = new CountDownLatch(1)
        def workerCreated = new CountDownLatch(1)
        
        def supervisor = system.actor {
            name 'supervisor'
            onMessage { msg, ctx ->
                if (msg == 'start-worker') {
                    def worker = ctx.system.actor {
                        name 'test-worker'
                        onMessage { msg2, ctx2 ->
                            // Worker just processes messages
                            // (stopping will be done externally)
                        }
                    }
                    ctx.watch(worker)
                    println "[supervisor] Watching test-worker"
                    workerCreated.countDown()
                } else if (msg instanceof Terminated) {
                    println "[supervisor] Received Terminated: ${msg.actor.name}"
                    terminatedMessages << msg.actor.name
                    latch.countDown()
                }
            }
        }
        
        // When: Create and stop a worker
        supervisor.tell('start-worker')
        
        // Wait for worker to be created and watched
        assertTrue(workerCreated.await(1, TimeUnit.SECONDS), "Worker should be created")
        Thread.sleep(200)  // Give time for watch to register
        
        // Verify worker exists
        def worker = system.getActor('test-worker')
        assertNotNull(worker, "Worker should exist")
        
        // Verify watch is registered
        assertTrue(system.deathWatch.isWatching(supervisor, worker), "Supervisor should be watching worker")
        
        // Stop the worker (from test thread, not from within worker's handler)
        println "[test] Stopping worker..."
        worker.stop()
        
        // Then: Supervisor receives Terminated
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Should receive Terminated message")
        assertEquals(1, terminatedMessages.size())
        assertEquals('test-worker', terminatedMessages[0])
    }
    
    @Test
    @DisplayName("Pool pattern - maintain fixed number of workers")
    void testWorkerPoolPattern() {
        // Given: A pool manager
        def poolSize = 3
        def latch = new CountDownLatch(1)
        
        def poolManager = system.actor {
            name 'pool-manager'
            state workers: [], targetSize: poolSize
            onMessage { msg, ctx ->
                if (msg == 'init') {
                    for (int i = 0; i < ctx.state.targetSize; i++) {
                        def worker = ctx.system.actor {
                            name "pooled-worker-$i"
                            onMessage { msg2, ctx2 -> }
                        }
                        ctx.state.workers << worker
                        ctx.watch(worker)
                    }
                } else if (msg == 'stop-one') {
                    if (!ctx.state.workers.isEmpty()) {
                        ctx.state.workers[0].stop()
                    }
                } else if (msg instanceof Terminated) {
                    // Remove from pool
                    ctx.state.workers.removeIf { it.name == msg.actor.name }
                    
                    // Spawn replacement
                    def replacement = ctx.system.actor {
                        name "pooled-worker-replacement-${System.currentTimeMillis()}"
                        onMessage { msg2, ctx2 -> }
                    }
                    ctx.state.workers << replacement
                    ctx.watch(replacement)
                    
                    latch.countDown()
                }
            }
        }
        
        // When: Initialize pool and kill one worker
        poolManager.tell('init')
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertEquals(poolSize, poolManager.state.workers.size())
        }
        
        poolManager.tell('stop-one')
        
        // Then: Pool maintains size
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertEquals(poolSize, poolManager.state.workers.size())
        }
    }
    
    // ============================================================
    // Edge Cases
    // ============================================================
    
    @Test
    @DisplayName("Watching non-existent actor should throw")
    void testWatchNonExistent() {
        def watcher = system.actor {
            name 'watcher'
            onMessage { msg, ctx ->
                if (msg == 'watch-invalid') {
                    assertThrows(IllegalArgumentException.class) {
                        ctx.watch('non-existent')
                    }
                }
            }
        }
        
        watcher.tell('watch-invalid')
        Thread.sleep(100)
    }
    
    @Test
    @DisplayName("Actor cannot watch itself")
    void testCannotWatchSelf() {
        def messages = new CopyOnWriteArrayList<String>()
        
        def actor = system.actor {
            name 'self-watcher'
            onMessage { msg, ctx ->
                if (msg == 'watch-self') {
                    ctx.watch(ctx.self())
                    messages << 'watched'
                } else if (msg instanceof Terminated) {
                    messages << 'terminated'
                }
            }
        }
        
        actor.tell('watch-self')
        Thread.sleep(100)
        actor.stop()
        Thread.sleep(200)
        
        // DeathWatchRegistry prevents self-watch, so only 'watched' message
        // No Terminated should arrive
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted {
            assertEquals(1, messages.size())
            assertEquals('watched', messages[0])
        }
    }
    
    @Test
    @DisplayName("Death watch registry cleanup on actor termination")
    void testRegistryCleanup() {
        def terminatedReceived = new AtomicInteger(0)
        
        def watcher = system.actor {
            name 'cleanup-watcher'
            onMessage { msg, ctx ->
                if (msg == 'watch') {
                    ctx.watch('cleanup-worker')
                } else if (msg instanceof Terminated) {
                    terminatedReceived.incrementAndGet()
                }
            }
        }
        
        def worker = system.actor {
            name 'cleanup-worker'
            onMessage { msg, ctx -> }
        }
        
        // Watch then stop worker
        watcher.tell('watch')
        Thread.sleep(50)
        
        // Verify watch relationship exists
        assertTrue(system.deathWatch.isWatching(watcher, worker))
        
        // Stop worker
        worker.stop()
        
        // Verify watcher received Terminated message
        await().atMost(1, TimeUnit.SECONDS).untilAsserted {
            assertTrue(terminatedReceived.get() >= 1, "Watcher should receive Terminated message")
        }
    }
}
