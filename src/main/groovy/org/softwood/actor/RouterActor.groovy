package org.softwood.actor

import groovy.transform.CompileStatic

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ThreadLocalRandom

@CompileStatic
class RouterActor extends ScopedValueActor<Object> {

    private final List<ScopedValueActor<?>> routees
    private final RouterStrategy strategy
    private final AtomicInteger index = new AtomicInteger(0)

    RouterActor(String name,
                List<ScopedValueActor<?>> routees,
                RouterStrategy strategy = RouterStrategy.ROUND_ROBIN) {

        super(name, new ScopedValueActor.MessageHandler<Object>() {
            @Override
            Object handle(Object msg, ScopedValueActor.ActorContext ctx) {
                RouterActor self = (RouterActor) ctx.actor
                self.route(msg)
                return null
            }
        })

        this.routees = new ArrayList<ScopedValueActor<?>>(routees)
        this.strategy = strategy
    }

    private void route(Object msg) {
        if (routees.isEmpty()) {
            println "[Router ${getName()}] No routees to send to"
            return
        }

        switch (strategy) {
            case RouterStrategy.BROADCAST:
                for (ScopedValueActor<?> a : routees) {
                    @SuppressWarnings("unchecked")
                    ScopedValueActor<Object> typed = (ScopedValueActor<Object>) a
                    typed.tell(msg)
                }
                break

            case RouterStrategy.ROUND_ROBIN:
                int i = Math.floorMod(index.getAndIncrement(), routees.size())
                forwardTo(routees.get(i), msg)
                break

            case RouterStrategy.RANDOM:
                int r = ThreadLocalRandom.current().nextInt(routees.size())
                forwardTo(routees.get(r), msg)
                break
        }
    }

    private void forwardTo(ScopedValueActor<?> target, Object msg) {
        @SuppressWarnings("unchecked")
        ScopedValueActor<Object> typed = (ScopedValueActor<Object>) target
        typed.tell(msg)
    }

    List<ScopedValueActor<?>> getRoutees() {
        return Collections.unmodifiableList(routees)
    }

    RouterStrategy getStrategy() {
        return strategy
    }
}
