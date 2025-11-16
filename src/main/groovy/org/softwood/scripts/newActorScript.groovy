package org.softwood.scripts

import org.softwood.actor.ActorSystem

import static org.softwood.actor.ActorDSL.actor

def system = new ActorSystem("MyActorWorld")

// add actor Using static DSL
def printer = actor(system) {
    name "Printer"
    onMessage { msg, ctx ->
        println "[$ctx.actorName] received: $msg"
    }
}

// add actor Using system.actor { }
def echo = system.actor {
    name "Echo"
    onMessage { msg, ctx ->
        ctx.reply("Echo: $msg")
    }
}

// Tell (fire-and-forget)
printer.tell("Hello World")

// Ask (request-reply)
def response = echo.askSync("Test")
assert response == "Echo: Test"


println "--- stateful actor with initial state ----"
def counter = system.actor {
    name "Counter"
    state count: 0, multiplier: 2


        react { msg, ctx ->
            def current = ctx.state.count
            ctx.state.count = current + 1
            current * ctx.state.multiplier
        }

}

assert counter.askSync("increment") == 0   // 0 * 2
assert counter.askSync("increment") == 2   // 1 * 2
assert counter.askSync("increment") == 4   // 2 * 2
assert counter.state.count == 3

println "--- async continuation pattern, calculator  --- "
def calculator = system.actor {
    name "Calculator"
    onMessage { msg, ctx ->
        if (msg instanceof Map && msg.op == "add") {
            msg.a + msg.b
        }
    }
}

calculator.sendAndContinue([op: "add", a: 5, b: 3]) { result ->
    println "calculator Got async result: $result"  // 8
}

println "------ actor communication ----- "
def logger = system.actor {
    name "Logger"
    onMessage { msg ->
        println "[LOG] $msg"
    }
}

def worker = system.actor {
    name "Worker"
    onMessage { msg, ctx ->
        def loggerActor = system.getActor("Logger")
        loggerActor.tell("Worker processing: $msg")
        "Done: $msg"
    }
}

worker.tell("Task1")

system.shutdown()