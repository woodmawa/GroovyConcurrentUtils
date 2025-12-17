package org.softwood.scripts

import org.softwood.actor.ActorSystem

import java.time.Duration

def system = new ActorSystem('test')

def actor = system.actor {
    name 'timer-demo'
    onMessage { msg, ctx ->
        if (msg == 'start') {
            println "Scheduling timeout in 2 seconds..."
            ctx.scheduleOnce(Duration.ofSeconds(2), 'timeout')
        } else if (msg == 'timeout') {
            println "⏰ Timeout fired!"
        }
    }
}

actor.tell('start')
Thread.sleep(3000)
system.shutdown()