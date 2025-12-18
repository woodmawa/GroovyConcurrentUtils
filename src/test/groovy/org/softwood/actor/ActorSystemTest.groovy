package org.softwood.actor

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test
import java.time.Duration

// Groovy's built-in testing classes
import groovy.mock.interceptor.MockFor
import groovy.mock.interceptor.StubFor

import org.softwood.actor.remote.RemotingTransport
import org.softwood.actor.remote.RemoteActorRef

// JUnit 5 imports
import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for ActorSystem using JUnit 5 and Groovy's built-in mocking.
 */
class ActorSystemTest {

    // ═════════════════════════════════════════════════════════════
    // Original Integration-Style Test (No Mocks)
    // ═════════════════════════════════════════════════════════════

    @Test
    @CompileDynamic
    void testCreateAndShutdownSystem() {
        def system = new ActorSystem("SysTest")

        def a = system.actor {
            name "A1"
            onMessage { msg, ctx ->
                ctx.reply("OK:$msg")
            }
        }

        def result = a.askSync("pong", Duration.ofSeconds(2))
        assertEquals("OK:pong", result)

        system.shutdown()
    }

    // ═════════════════════════════════════════════════════════════
    // Unit Tests with Stubs (Verifying Registry Delegation)
    // ═════════════════════════════════════════════════════════════

    @Test
    @CompileDynamic
    void testGetActorDelegatesToRegistry() {
        // 1. Arrange

        // 1a. Setup mock actor using ActorFactory
        def dummyHandler = { msg, ctx -> /* no-op */ }
        def mockActor = ActorFactory.create("myActor", dummyHandler)

        // Create a stable, anonymous subclass of ActorRegistry.
        def mockRegistry = new ActorRegistry() {

            @Override
            boolean isDistributed() {
                return false
            }

            @Override
            Actor get(String name) {
                assertEquals "myActor", name
                return mockActor
            }
        }

        def system = new ActorSystem("test-system", mockRegistry)

        // 2. Act
        def result = system.getActor("myActor")

        // 3. Assert
        assertEquals(mockActor, result)
        
        // Cleanup
        mockActor.stop()
    }

    // ═════════════════════════════════════════════════════════════
    // Unit Tests with Mocks (Verifying Method Calls)
    // ═════════════════════════════════════════════════════════════

    @Test
    @CompileDynamic
    void testRemoveActorStopsAndUnregisters() {
        // Manual verification flags/counters
        def stopCalled = false
        def unregisterCalled = false

        // Create mock actor using ActorFactory
        def dummyHandler = { msg, ctx -> /* no-op */ }
        def mockActor = ActorFactory.create("actor-to-remove", dummyHandler)
        
        // Wrap it to track stop calls
        def trackedActor = new Actor() {
            @Override void tell(Object msg) { mockActor.tell(msg) }
            @Override void tell(Object msg, Actor sender) { mockActor.tell(msg, sender) }
            @Override void send(Object msg) { mockActor.send(msg) }
            @Override Object ask(Object msg, Duration timeout) { mockActor.ask(msg, timeout) }
            @Override Object askSync(Object msg, Duration timeout) { mockActor.askSync(msg, timeout) }
            @Override void sendAndContinue(Object msg, Closure continuation, Duration timeout) { 
                mockActor.sendAndContinue(msg, continuation, timeout) 
            }
            @Override Object sendAndWait(Object msg, Duration timeout) { mockActor.sendAndWait(msg, timeout) }
            @Override Object leftShift(Object msg) { mockActor.leftShift(msg) }
            @Override String getName() { mockActor.getName() }
            @Override Map getState() { mockActor.getState() }
            @Override void stop() { stopCalled = true; mockActor.stop() }
            @Override boolean stop(Duration timeout) { stopCalled = true; return mockActor.stop(timeout) }
            @Override void stopNow() { stopCalled = true; mockActor.stopNow() }
            @Override boolean isStopped() { mockActor.isStopped() }
            @Override boolean isTerminated() { mockActor.isTerminated() }
            @Override Actor onError(Closure handler) { mockActor.onError(handler) }
            @Override List getErrors(int maxCount) { mockActor.getErrors(maxCount) }
            @Override void clearErrors() { mockActor.clearErrors() }
            @Override void setMaxMailboxSize(int max) { mockActor.setMaxMailboxSize(max) }
            @Override int getMaxMailboxSize() { mockActor.getMaxMailboxSize() }
            @Override Map health() { mockActor.health() }
            @Override Map metrics() { mockActor.metrics() }
        }

        // Create mock registry
        def mockRegistry = new ActorRegistry() {
            @Override
            boolean isDistributed() {
                return false
            }

            @Override
            Actor get(String name) {
                assertEquals "actor-to-remove", name
                return trackedActor
            }

            @Override
            void unregister(String name) {
                assertEquals "actor-to-remove", name
                unregisterCalled = true
            }
        }

        def system = new ActorSystem("test-system", mockRegistry)

        // Act
        system.removeActor("actor-to-remove")

        // Assert
        assertTrue(stopCalled, "Expected actor.stop() to be called once by removeActor.")
        assertTrue(unregisterCalled, "Expected registry.unregister() to be called once by removeActor.")
    }

    @Test
    @CompileDynamic
    void testShutdownStopsAllActorsAndClearsRegistry() {
        // Manual tracking flags/counters
        def stopCount = 0
        def clearCalled = false

        def dummyHandler = { msg, ctx -> /* no-op */ }

        // Create actors using ActorFactory
        def mockActor1 = ActorFactory.create("A1", dummyHandler)
        def mockActor2 = ActorFactory.create("A2", dummyHandler)
        
        // Wrap them to track stop calls
        def trackedActor1 = new Actor() {
            @Override void tell(Object msg) { mockActor1.tell(msg) }
            @Override void tell(Object msg, Actor sender) { mockActor1.tell(msg, sender) }
            @Override void send(Object msg) { mockActor1.send(msg) }
            @Override Object ask(Object msg, Duration timeout) { mockActor1.ask(msg, timeout) }
            @Override Object askSync(Object msg, Duration timeout) { mockActor1.askSync(msg, timeout) }
            @Override void sendAndContinue(Object msg, Closure continuation, Duration timeout) { 
                mockActor1.sendAndContinue(msg, continuation, timeout) 
            }
            @Override Object sendAndWait(Object msg, Duration timeout) { mockActor1.sendAndWait(msg, timeout) }
            @Override Object leftShift(Object msg) { mockActor1.leftShift(msg) }
            @Override String getName() { mockActor1.getName() }
            @Override Map getState() { mockActor1.getState() }
            @Override void stop() { stopCount++; mockActor1.stop() }
            @Override boolean stop(Duration timeout) { stopCount++; return mockActor1.stop(timeout) }
            @Override void stopNow() { stopCount++; mockActor1.stopNow() }
            @Override boolean isStopped() { mockActor1.isStopped() }
            @Override boolean isTerminated() { mockActor1.isTerminated() }
            @Override Actor onError(Closure handler) { mockActor1.onError(handler) }
            @Override List getErrors(int maxCount) { mockActor1.getErrors(maxCount) }
            @Override void clearErrors() { mockActor1.clearErrors() }
            @Override void setMaxMailboxSize(int max) { mockActor1.setMaxMailboxSize(max) }
            @Override int getMaxMailboxSize() { mockActor1.getMaxMailboxSize() }
            @Override Map health() { mockActor1.health() }
            @Override Map metrics() { mockActor1.metrics() }
        }
        
        def trackedActor2 = new Actor() {
            @Override void tell(Object msg) { mockActor2.tell(msg) }
            @Override void tell(Object msg, Actor sender) { mockActor2.tell(msg, sender) }
            @Override void send(Object msg) { mockActor2.send(msg) }
            @Override Object ask(Object msg, Duration timeout) { mockActor2.ask(msg, timeout) }
            @Override Object askSync(Object msg, Duration timeout) { mockActor2.askSync(msg, timeout) }
            @Override void sendAndContinue(Object msg, Closure continuation, Duration timeout) { 
                mockActor2.sendAndContinue(msg, continuation, timeout) 
            }
            @Override Object sendAndWait(Object msg, Duration timeout) { mockActor2.sendAndWait(msg, timeout) }
            @Override Object leftShift(Object msg) { mockActor2.leftShift(msg) }
            @Override String getName() { mockActor2.getName() }
            @Override Map getState() { mockActor2.getState() }
            @Override void stop() { stopCount++; mockActor2.stop() }
            @Override boolean stop(Duration timeout) { stopCount++; return mockActor2.stop(timeout) }
            @Override void stopNow() { stopCount++; mockActor2.stopNow() }
            @Override boolean isStopped() { mockActor2.isStopped() }
            @Override boolean isTerminated() { mockActor2.isTerminated() }
            @Override Actor onError(Closure handler) { mockActor2.onError(handler) }
            @Override List getErrors(int maxCount) { mockActor2.getErrors(maxCount) }
            @Override void clearErrors() { mockActor2.clearErrors() }
            @Override void setMaxMailboxSize(int max) { mockActor2.setMaxMailboxSize(max) }
            @Override int getMaxMailboxSize() { mockActor2.getMaxMailboxSize() }
            @Override Map health() { mockActor2.health() }
            @Override Map metrics() { mockActor2.metrics() }
        }

        // Create mock registry
        def mockRegistry = new ActorRegistry() {
            @Override
            boolean isDistributed() {
                return false
            }

            @Override
            List<Actor> getAllActors() {
                return [trackedActor1, trackedActor2]
            }

            @Override
            void clear() {
                clearCalled = true
            }
        }

        def system = new ActorSystem("test-system", mockRegistry)

        // Act
        system.shutdown()

        // Assert
        assertEquals(2, stopCount, "Expected stop() to be called twice on the actors.")
        assertTrue(clearCalled, "Expected registry.clear() to be called once.")
    }

    // ═════════════════════════════════════════════════════════════
    // New Remoting Feature Tests
    // ═════════════════════════════════════════════════════════════

    @Test
    @CompileDynamic
    void testEnableRemotingAndRemoteHappyPath() {
        // 1. Arrange
        // 🚨 FINAL FIX: Create a manual, anonymous implementation of the interface.
        // This bypasses ALL Groovy metaclass instability, guaranteeing the test runs.
        def mockTransportInstance = [
                // Define the methods required by RemotingTransport
                scheme: { -> "http" },
                start: { -> /* no-op */ },

                // Define other methods (tell, ask, close) if required by the test runner/interface
                tell: { String uri, Object msg -> /* no-op */ },
                ask: { String uri, Object msg, Duration timeout -> null },
                close: { -> /* no-op */ }

        ] as RemotingTransport // Use 'as' keyword to coerce the map into the interface

        def system = new ActorSystem("test-system", new ActorRegistry())

        // 2. Act
        // No 'use' block is needed now, as we are using a manual mock.
        system.enableRemoting([mockTransportInstance])

        // 3. Assert (End-to-end verification)
        // This still validates the logic: that the transport was registered.
        def uri = "http://server:8080/system/actor1"
        def remoteRef = system.remote(uri)

        assertNotNull(remoteRef)
        assertTrue(remoteRef instanceof RemoteActorRef)
        assertEquals(uri, ((RemoteActorRef)remoteRef).uri)
    }

    @Test
    @CompileDynamic
    void testRemoteFailsForUnregisteredScheme() {
        // 1. Arrange
        def system = new ActorSystem("test-system", new ActorRegistry())

        // 2. Act & Assert
        def ex = assertThrows(IllegalStateException.class, {
            system.remote("tcp://server:1234/system/actor2")
        })
        assertTrue(ex.message.contains("No remoting transport registered for scheme 'tcp'"))
    }

    @Test
    @CompileDynamic
    void testRemoteFailsForInvalidURI() {
        // 1. Arrange
        def system = new ActorSystem("test-system", new ActorRegistry())

        // 2. Act & Assert (No scheme)
        def ex1 = assertThrows(IllegalArgumentException.class, {
            system.remote("just-an-actor-name")
        })
        assertTrue(ex1.message.contains("Invalid actor URI"))

        // 3. Act & Assert (Empty scheme)
        def ex2 = assertThrows(IllegalArgumentException.class, {
            system.remote("://server/actor")
        })
        assertTrue(ex2.message.contains("Invalid actor URI"))
    }
}