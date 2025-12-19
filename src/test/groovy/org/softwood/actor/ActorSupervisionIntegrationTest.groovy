package org.softwood.actor

import org.junit.jupiter.api.*
import org.softwood.actor.supervision.SupervisionStrategy
import org.softwood.actor.supervision.SupervisorDirective

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

import static org.awaitility.Awaitility.*
import static org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ActorSupervisionIntegrationTest {

    ActorSystem system

    @BeforeEach
    void setup() {
        system = new ActorSystem('test-supervision')
    }

    @AfterEach
    void cleanup() {
        system?.shutdown()
    }

    @Test
    @DisplayName("RESTART directive should restart actor and clear state")
    void testRestartDirectiveClearsState() {
        def restartCount = new AtomicInteger(0)
        def actor = system.actor {
            name 'restartable-actor'
            supervisionStrategy SupervisionStrategy.restartAlways()
            state counter: 0
            onMessage { msg, ctx ->
                if (msg == 'fail') {
                    if (!ctx.state.containsKey('counter')) {
                        ctx.state.counter = 0
                    }
                    ctx.state.counter++
                    restartCount.set(ctx.state.counter as int)
                    throw new RuntimeException('Boom!')
                }
                if (!ctx.state.containsKey('counter')) {
                    ctx.state.counter = 0
                }
                ctx.reply("processed: ${msg}, counter: ${ctx.state.counter}")
            }
        }

        def result1 = actor.ask('hello')
        assertEquals('processed: hello, counter: 0', result1)

        actor.tell('fail')
        await().atMost(1, TimeUnit.SECONDS).untilAsserted {
            assertTrue(restartCount.get() > 0)
        }

        def result2 = actor.ask('world')
        assertEquals('processed: world, counter: 0', result2)
    }

    @Test
    @DisplayName("RESTART should invoke onRestart callback")
    void testRestartInvokesCallback() {
        def onRestartCalled = new AtomicBoolean(false)

        def strategy = new SupervisionStrategy() {
            @Override
            SupervisorDirective defaultDecision(Throwable throwable) {
                return SupervisorDirective.RESTART
            }

            @Override
            void onRestart(Object actor, Throwable cause) {
                onRestartCalled.set(true)
            }
        }

        def actor = system.actor {
            name 'callback-actor'
            supervisionStrategy strategy
            onMessage { msg, ctx ->
                if (msg == 'fail') {
                    throw new IllegalStateException('Test failure')
                }
            }
        }

        actor.tell('fail')

        await().atMost(1, TimeUnit.SECONDS).untilAsserted {
            assertTrue(onRestartCalled.get())
        }
    }

    @Test
    @DisplayName("RESUME directive should ignore error and continue")
    void testResumeIgnoresError() {
        def processedMessages = new CopyOnWriteArrayList<String>()

        def actor = system.actor {
            name 'resume-actor'
            supervisionStrategy SupervisionStrategy.resumeAlways()
            onMessage { msg, ctx ->
                if (msg == 'fail') {
                    throw new RuntimeException('Boom!')
                }
                processedMessages << msg
                ctx.reply("ok")
            }
        }

        actor.ask('msg1')
        actor.tell('fail')
        Thread.sleep(50)
        actor.ask('msg2')

        await().atMost(1, TimeUnit.SECONDS).untilAsserted {
            assertEquals(2, processedMessages.size())
        }
    }

    @Test
    @DisplayName("STOP directive should terminate actor")
    void testStopDirectiveTerminatesActor() {
        def actor = system.actor {
            name 'stop-actor'
            supervisionStrategy SupervisionStrategy.stopAlways()
            onMessage { msg, ctx ->
                if (msg == 'fail') {
                    throw new RuntimeException('Boom!')
                }
                ctx.reply("ok")
            }
        }

        actor.tell('fail')

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            assertTrue(actor.isStopped())
        }
    }

    @Test
    @DisplayName("STOP should invoke onStop callback")
    void testStopInvokesCallback() {
        def onStopCalled = new AtomicBoolean(false)

        def strategy = new SupervisionStrategy() {
            @Override
            SupervisorDirective defaultDecision(Throwable throwable) {
                return SupervisorDirective.STOP
            }

            @Override
            void onStop(Object actor, Throwable cause) {
                onStopCalled.set(true)
            }
        }

        def actor = system.actor {
            name 'stop-callback-actor'
            supervisionStrategy strategy
            onMessage { msg, ctx ->
                throw new RuntimeException('Stop me!')
            }
        }

        actor.tell('anything')

        await().atMost(2, TimeUnit.SECONDS).untilAsserted {
            assertTrue(onStopCalled.get())
        }
    }

    @Test
    @DisplayName("Should stop after max restarts")
    void testMaxRestartsExceeded() {
        def actor = system.actor {
            name 'limited-restarts'
            supervisionStrategy(
                    maxRestarts: 3,
                    withinDuration: Duration.ofSeconds(10)
            ) { throwable ->
                SupervisorDirective.RESTART
            }
            onMessage { msg, ctx ->
                throw new RuntimeException('Always fail')
            }
        }

        5.times { actor.tell("msg$it") }

        await().atMost(3, TimeUnit.SECONDS).untilAsserted {
            assertTrue(actor.isStopped())
        }
    }

    @Test
    @DisplayName("DSL should support all supervision strategy forms")
    void testDslSupportsAllStrategyForms() {
        def actor1 = system.actor {
            name 'dsl-object-form'
            supervisionStrategy SupervisionStrategy.restartAlways()
            onMessage { msg, ctx -> ctx.reply('ok') }
        }

        assertNotNull(actor1.supervisionStrategy)

        def actor2 = system.actor {
            name 'dsl-closure-form'
            supervisionStrategy { throwable ->
                SupervisorDirective.RESTART
            }
            onMessage { msg, ctx -> ctx.reply('ok') }
        }

        assertNotNull(actor2.supervisionStrategy)
    }

    @Test
    @DisplayName("Supervision should still record errors")
    void testSupervisionRecordsErrors() {
        def actor = system.actor {
            name 'error-tracking-actor'
            supervisionStrategy SupervisionStrategy.restartAlways()
            onMessage { msg, ctx ->
                if (msg == 'fail') {
                    throw new RuntimeException('Tracked error')
                }
                ctx.reply('ok')
            }
        }

        actor.tell('fail')

        await().atMost(1, TimeUnit.SECONDS).untilAsserted {
            def errors = actor.getErrors()
            assertTrue(errors.size() >= 1)
            assertEquals('RuntimeException', errors[0].errorType)
        }
    }
}