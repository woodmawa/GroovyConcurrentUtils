package org.softwood.dag.resilience

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.TaskContext
import spock.lang.Specification


import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class MemoSpec extends Specification {

    // =========================================================================
    // MemoCache unit tests
    // =========================================================================

    def "MemoCache stores and retrieves a value"() {
        given:
        def cache = new MemoCache()

        when:
        cache.put("k1", "hello")

        then:
        cache.get("k1") == "hello"
        cache.size() == 1
    }

    def "MemoCache returns null on miss"() {
        given:
        def cache = new MemoCache()

        expect:
        cache.get("missing") == null
    }

    def "MemoCache respects TTL expiry"() {
        given:
        def cache = new MemoCache()
        cache.put("k", "val", Duration.ofMillis(50))

        when:
        Thread.sleep(100)

        then:
        cache.get("k") == null
    }

    def "MemoCache does not expire entry without TTL"() {
        given:
        def cache = new MemoCache()
        cache.put("k", "val")          // no TTL
        Thread.sleep(50)

        expect:
        cache.get("k") == "val"
    }

    def "MemoCache evicts oldest when maxSize reached"() {
        given:
        def cache = new MemoCache(maxSize: 3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)

        when:
        cache.put("d", 4)   // should evict oldest (a)

        then:
        cache.size() == 3
        cache.stats().evictions == 1
    }

    def "MemoCache stats track hits and misses"() {
        given:
        def cache = new MemoCache()
        cache.put("x", 42)

        when:
        cache.get("x")    // hit
        cache.get("x")    // hit
        cache.get("y")    // miss

        then:
        cache.stats().hits   == 2
        cache.stats().misses == 1
        cache.stats().hitRate == 66.7
    }

    def "MemoCache invalidate removes entry"() {
        given:
        def cache = new MemoCache()
        cache.put("k", "v")

        when:
        cache.invalidate("k")

        then:
        cache.get("k") == null
        cache.size() == 0
    }

    def "MemoCache invalidateAll clears everything"() {
        given:
        def cache = new MemoCache()
        cache.put("a", 1)
        cache.put("b", 2)

        when:
        cache.invalidateAll()

        then:
        cache.size() == 0
    }

    // =========================================================================
    // MemoPolicy unit tests
    // =========================================================================

    def "MemoPolicy derives default key from taskId and input hashCode"() {
        given:
        def policy = new MemoPolicy(enabled: true)

        when:
        def key = policy.deriveKey("my-task", null, "someInput")

        then:
        key == "my-task:${"someInput".hashCode()}"
    }

    def "MemoPolicy uses staticKey when set"() {
        given:
        def policy = new MemoPolicy(enabled: true, staticKey: "fixed-key")

        expect:
        policy.deriveKey("t", null, "anything") == "fixed-key"
    }

    def "MemoPolicy uses keyFrom closure"() {
        given:
        def policy = new MemoPolicy(enabled: true,
            keyFrom: { ctx, input -> "user:${input.id}" })

        expect:
        policy.deriveKey("t", null, [id: 99]) == "user:99"
    }

    def "MemoPolicy presets set correct values"() {
        expect:
        MemoPolicy.shortLived().ttl == Duration.ofMinutes(5)
        MemoPolicy.longLived().ttl  == Duration.ofHours(1)
        MemoPolicy.sessionLived().ttl  == null
        MemoPolicy.persistent().crossRun == true
    }

    // =========================================================================
    // MemoDsl unit tests
    // =========================================================================

    def "MemoDsl configures policy correctly"() {
        given:
        def policy = new MemoPolicy()
        def dsl    = new MemoDsl(policy)

        when:
        dsl.keyFrom { ctx, input -> "k:${input}" }
        dsl.ttl Duration.ofMinutes(3)

        then:
        policy.enabled == true
        policy.ttl == Duration.ofMinutes(3)
        policy.keyFrom != null
    }

    def "MemoDsl preset 'short' sets 5-minute TTL"() {
        given:
        def policy = new MemoPolicy()

        when:
        new MemoDsl(policy).preset("short")

        then:
        policy.enabled == true
        policy.ttl == Duration.ofMinutes(5)
    }

    def "MemoDsl preset throws on unknown name"() {
        when:
        new MemoDsl(new MemoPolicy()).preset("nonexistent")

        then:
        thrown(IllegalArgumentException)
    }

    // =========================================================================
    // Integration: memo wired via TaskGraph
    // =========================================================================

    def "task with memo{} block skips re-execution on second call with same input"() {
        given:
        def callCount = new AtomicInteger(0)
        def graph = TaskGraph.build {
            task("compute") {
                memo {
                    keyFrom { c, input -> "compute:${input}" }
                }
                action { c, prev ->
                    callCount.incrementAndGet()
                    "result-${prev}"
                }
            }
        }

        when: "first run"
        graph.execute("input-A").get()

        then:
        callCount.get() == 1

        when: "second run - same input, same graph ctx (memo cache shared)"
        graph.reset()
        graph.execute("input-A").get()

        then: "action should NOT be called again"
        callCount.get() == 1
    }

    def "task with memo{} runs again when input changes"() {
        given:
        def callCount = new AtomicInteger(0)
        def graph = TaskGraph.build {
            task("compute") {
                memo {
                    keyFrom { c, input -> "compute:${input}" }
                }
                action { c, prev ->
                    callCount.incrementAndGet()
                    "result-${prev}"
                }
            }
        }

        when:
        graph.execute("input-A").get()
        graph.reset()
        graph.execute("input-B").get()

        then:
        callCount.get() == 2
    }

    def "memo preset string shorthand works"() {
        given:
        def graph = TaskGraph.build {
            task("t") {
                memo "short"
                action { ctx, prev -> "ok" }
            }
        }

        when:
        graph.execute(null).get()

        then:
        graph.tasks["t"].memoPolicy.enabled == true
        graph.tasks["t"].memoPolicy.ttl == Duration.ofMinutes(5)
    }

    def "TaskContext lazily creates MemoCache on demand"() {
        given:
        def ctx = new TaskContext()

        expect:
        ctx.@memoCache == null           // not yet created

        when:
        def cache = ctx.memoCache        // lazy init

        then:
        cache != null
        ctx.@memoCache != null
    }
}
