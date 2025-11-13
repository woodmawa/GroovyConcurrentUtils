package org.softwood.actor

import groovy.transform.CompileStatic
import java.time.Duration
import static org.softwood.actor.Actors.actor

@CompileStatic
class TestCode {

    static void main(String[] args) {
        println "\n=== ACTOR SYSTEM V2 TEST SUITE ===\n"

        demoDSLActors()
        demoAccountActors()
        demoGroups()
        demoBehaviorSwitch()

        println "\n=== ALL DEMOS COMPLETE ===\n"
    }

    // -------------------------------------------------------------------------
    static void demoDSLActors() {
        println "\n=== DEMO 1: DSL Actors + ActorSystem ==="

        ActorSystem system = new ActorSystem("Demo1")

        def printer = actor {
            name "Printer"
            onMessage { Object msg, ScopedValueActor.ActorContext ctx ->
                println "[Printer] $msg"
                return null
            }
        }

        def counter = actor {
            name "Counter"
            loop {
                react { Object msg, ScopedValueActor.ActorContext ctx ->
                    int n = ((ctx.get("n") ?: 0) as int) + 1
                    ctx.set("n", n)
                    println "[Counter] count=$n"
                    return n
                }
            }
        }

        system.register(printer)
        system.register(counter)

        printer.tell("Hello world!")
        counter.tell([:])

        println "Counter reply: ${counter.askSync([:])}"
        println "Registry names: ${system.actorNames}"

        system.shutdown()
    }

    // -------------------------------------------------------------------------
    static void demoAccountActors() {
        println "\n=== DEMO 2: Account Actors ==="

        ActorSystem bank = new ActorSystem("BankSystem")

        ScopedValueActor.MessageHandler<Map> accountHandler =
                new ScopedValueActor.MessageHandler<Map>() {
                    @Override
                    Object handle(Map msg, ScopedValueActor.ActorContext ctx) {
                        double bal = (ctx.get("balance") ?: 0.0) as double
                        switch (msg.action) {
                            case "deposit":  bal += msg.amount; break
                            case "withdraw": bal -= msg.amount; break
                        }
                        ctx.set("balance", bal)
                        println "[${ctx.actorName}] new balance $bal"
                        return bal
                    }
                }

        def alice = bank.<Map>createActor("Alice", accountHandler)
        def bob   = bank.<Map>createActor("Bob",   accountHandler)

        alice.tell([action: "deposit", amount: 100.0])
        bob.tell([action: "deposit", amount: 200.0])

        println "Alice sync deposit: ${alice.askSync([action:'deposit', amount:50.0])}"

        bank.shutdown()
    }

    // -------------------------------------------------------------------------
    static void demoGroups() {
        println "\n=== DEMO 3: Actor Groups ==="

        ActorSystem system = new ActorSystem("GroupSystem")

        def quick = actor {
            name "Quick"
            onMessage { Object msg, ScopedValueActor.ActorContext ctx ->
                Thread.sleep(50)
                return "Quick-response"
            }
        }

        def slow = actor {
            name "Slow"
            onMessage { Object msg, ScopedValueActor.ActorContext ctx ->
                Thread.sleep(500)
                return "Slow-response"
            }
        }

        system.register(quick)
        system.register(slow)

        def group = system.groupAll("all")
        group.tell("Broadcast message!")

        def fastest = group.askAny("Ping!", Duration.ofSeconds(2))
        println "askAny result: $fastest"

        system.shutdown()
    }

    // -------------------------------------------------------------------------
    static void demoBehaviorSwitch() {
        println "\n=== DEMO 4: Behavior Switching ==="

        ActorSystem system = new ActorSystem("SwitchSystem")

        def mood = actor {
            name "Mood"

            onMessage { Object msg, ScopedValueActor.ActorContext ctx ->
                String s = msg as String
                if (s == "flip") {
                    println "[Mood] switching to grumpy"
                    ctx.become { Object m2, ScopedValueActor.ActorContext ctx2 ->
                        String t = m2 as String
                        if (t == "flip") {
                            println "[Mood] switching back to happy"
                            ctx2.become { Object m3, ScopedValueActor.ActorContext ctx3 ->
                                println "[Mood] (happy) ${m3 as String}"
                                return null
                            }
                        } else {
                            println "[Mood] (grumpy) $t"
                        }
                        return null
                    }
                } else {
                    println "[Mood] (happy) $s"
                }
                return null
            }
        }

        system.register(mood)

        mood.tell("Hello")
        mood.tell("Test")
        mood.tell("flip")
        mood.tell("Another test")
        mood.tell("flip")
        mood.tell("Final message")

        Thread.sleep(300)

        system.shutdown()
    }
}
