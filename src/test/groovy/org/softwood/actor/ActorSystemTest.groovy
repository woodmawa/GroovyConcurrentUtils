package org.softwood.actor

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import java.time.Duration

import static org.junit.jupiter.api.Assertions.*
import static org.softwood.actor.ActorDSL.actor

class ActorSystemTest {

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
}
