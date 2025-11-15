package org.softwood.scripts

import org.softwood.actor.ActorSystem

import static org.softwood.actor.ActorDSL.actor

def system = new ActorSystem("Demo")

def counter = actor(system) {
    name "Counter"
    loop {
        react { msg, ctx ->
            def n = (ctx.state.count ?: 0) + 1
            ctx.state.count = n
            ctx.reply(n)
        }
    }
}

println counter.sendAndWait("tick")  // 1
println counter.sendAndWait("tick")  // 2

counter.stop()
system.shutdown()