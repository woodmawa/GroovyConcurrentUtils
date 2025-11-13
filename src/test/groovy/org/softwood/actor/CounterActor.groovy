package org.softwood.actor

/**
 * Example: Counter Actor
 */
class CounterActor extends ScopedValueActor<Map> {

    CounterActor(String name) {
        super(name, makeHandler())
    }

    private static ScopedValueActor.MessageHandler<Map> makeHandler() {
        return { message, ctx ->
            def action = message.action
            def count  = (ctx.get("count") ?: 0) as int

            switch (action) {
                case "increment":
                    count++
                    ctx.set("count", count)
                    println "[${ctx.actorName}] Incremented -> $count"
                    return count
                case "decrement":
                    count--
                    ctx.set("count", count)
                    println "[${ctx.actorName}] Decremented -> $count"
                    return count
                case "get":
                    return count
                case "reset":
                    ctx.set("count", 0)
                    println "[${ctx.actorName}] Reset to 0"
                    return 0
                default:
                    throw new IllegalArgumentException("Unknown action: $action")
            }
        } as ScopedValueActor.MessageHandler
    }
}
