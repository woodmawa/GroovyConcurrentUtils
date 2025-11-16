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
import static org.softwood.actor.ActorDSL.actor

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

        def a = actor(system) {
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

        // 1a. Setup mock actor (typed correctly via anonymous subclass)
        def dummyHandler = { msg, ctx -> /* no-op */ }
        def mockActor = new ScopedValueActor("myActor", [:], dummyHandler) {}

        // 🚨 FINAL FIX: Create a stable, anonymous subclass of ActorRegistry.
        // This allows us to override the required methods reliably without using StubFor.
        def mockRegistry = new ActorRegistry() {

            // This method is called in the ActorSystem constructor (line 45)
            @Override
            boolean isDistributed() {
                return false // Return a stable value to satisfy logging
            }

            // This method is the core delegation logic we are testing
            @Override
            ScopedValueActor get(String name) {
                assertEquals "myActor", name // Verify the argument passed by the system
                return mockActor
            }

            // You may need to add overrides for any other methods ActorSystem uses (e.g., size(), clear(), register())
        }

        // 1b. Inject the stable mock registry.
        // No StubFor/MockFor needed.
        def system = new ActorSystem("test-system", mockRegistry)

        // 2. Act
        // No 'use' block needed.
        def result = system.getActor("myActor")

        // 3. Assert
        assertEquals(mockActor, result)
    }

    // ═════════════════════════════════════════════════════════════
    // Unit Tests with Mocks (Verifying Method Calls)
    // ═════════════════════════════════════════════════════════════

    @Test
    @CompileDynamic
    void testRemoveActorStopsAndUnregisters() {
        // 1. Arrange

        // Manual verification flags/counters
        def stopCalled = false
        def unregisterCalled = false

        // 1a. Create a STABLE ANONYMOUS MOCK ACTOR
        def dummyHandler = { msg, ctx -> /* no-op */ }

        // This object is correctly typed and overrides 'stop' for tracking
        def mockActor = new ScopedValueActor("actor-to-remove", [:], dummyHandler) {
            @Override
            void stop() {
                stopCalled = true // Track the stop call manually
            }
        }

        // 1b. Create a STABLE ANONYMOUS MOCK REGISTRY
        // This object is correctly typed and handles all required delegation/logic.
        def mockRegistry = new ActorRegistry() {

            // Handles the call in ActorSystem constructor: log.info "... (distributed: ${registry.isDistributed()})"
            @Override
            boolean isDistributed() {
                return false
            }

            // Handles system.removeActor() calling registry.get(name)
            @Override
            ScopedValueActor get(String name) {
                assertEquals "actor-to-remove", name
                return mockActor // Returns the stable mock actor
            }

            // Handles system.removeActor() calling registry.unregister(name)
            @Override
            void unregister(String name) {
                assertEquals "actor-to-remove", name
                unregisterCalled = true // Track the unregister call manually
            }
        }

        // Inject the stable mock registry.
        def system = new ActorSystem("test-system", mockRegistry)

        // 2. Act
        // No 'use' block is necessary as we are not using MockFor/StubFor.
        system.removeActor("actor-to-remove")

        // 3. Assert
        assertTrue(stopCalled, "Expected actor.stop() to be called once by removeActor.")
        assertTrue(unregisterCalled, "Expected registry.unregister() to be called once by removeActor.")
    }

    @Test
    @CompileDynamic
    void testShutdownStopsAllActorsAndClearsRegistry() {
        // 1. Arrange

        // Manual tracking flags/counters
        def stopCount = 0
        def clearCalled = false

        // Define the mandatory Closure argument for the ScopedValueActor constructor
        def dummyHandler = { msg, ctx -> /* no-op */ }

        // 1a. Create anonymous subclass mocks for the actors
        def mockActor1 = new ScopedValueActor("A1", [:], dummyHandler) {
            @Override
            void stop() {
                stopCount++
            }
        }

        def mockActor2 = new ScopedValueActor("A2", [:], dummyHandler) {
            @Override
            void stop() {
                stopCount++
            }
        }

        // 1b. 🚨 FIX: Create a STABLE ANONYMOUS MOCK REGISTRY.
        def mockRegistry = new ActorRegistry() {

            // Handles the call in ActorSystem constructor: log.info "... (distributed: ${registry.isDistributed()})"
            @Override
            boolean isDistributed() {
                return false // Guarantees stable return value
            }

            // Handles system.shutdown() calling registry.getAllActors()
            @Override
            List<ScopedValueActor> getAllActors() {
                return [mockActor1, mockActor2]
            }

            // Handles system.shutdown() calling registry.clear()
            @Override
            void clear() {
                clearCalled = true // Manual verification
            }
        }

        // Inject the stable mock registry.
        def system = new ActorSystem("test-system", mockRegistry)

        // 2. Act
        // No 'use' block is necessary as we are not using StubFor.
        system.shutdown()

        // 3. Assert (Verification)
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