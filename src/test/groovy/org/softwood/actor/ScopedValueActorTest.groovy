package org.softwood.actor

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import java.time.Duration

import static org.junit.jupiter.api.Assertions.*

class ScopedValueActorTest {

    @Test
    @CompileDynamic
    void testEchoActor() {
        def actor = new ScopedValueActor("Echo") { msg, ctx ->
            return "ECHO:$msg"
        }

        def result = actor.askSync("hello", Duration.ofSeconds(2))
        assertEquals("ECHO:hello", result)

        actor.stop()
    }

    @Test
    @CompileDynamic
    void testStatefulCounter() {
        def actor = new ScopedValueActor("Counter") { msg, ctx ->
            def n = (ctx.state.count ?: 0) + 1
            ctx.state.count = n
            return n
        }

        assertEquals(1, actor.askSync("tick"))
        assertEquals(2, actor.askSync("tick"))
        assertEquals(3, actor.askSync("tick"))

        actor.stop()
    }
}
