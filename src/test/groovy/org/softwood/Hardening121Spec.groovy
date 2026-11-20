package org.softwood

import com.hazelcast.config.Config
import groovy.transform.CompileDynamic
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.serialization.JavaSerializer
import org.softwood.actor.remote.serialization.SerializationException
import org.softwood.actor.supervision.SupervisionStrategy
import org.softwood.agent.AgentFactory
import org.softwood.cluster.HazelcastManager
import org.softwood.cluster.HazelcastSecurityConfig
import org.softwood.config.ConfigLoader
import org.softwood.dag.TaskBinding
import org.softwood.pool.ConcurrentPool
import org.softwood.promise.PromiseImplementation
import org.softwood.promise.Promises
import spock.lang.Specification
import spock.lang.Timeout

import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * GCU 1.2.1 hardening (review gcu/review-2-2026-11-20).
 *
 * Each feature was run red against 1.2.0 before the fix it guards:
 *   A1 actor self-stop deadlock, A3 stop() drains / pending asks fail, A4 stop sentinel,
 *   G1 agent state version, S1 Java deserialisation filter, C1 Hazelcast security settings applied,
 *   F1 external config sources, M2 null-safe TaskBinding.set, DF8/P3 timers released, DF14 unwrapped cause.
 */
@CompileDynamic
@Timeout(60)
class Hardening121Spec extends Specification {

    static class Payload implements Serializable {
        private static final long serialVersionUID = 1L
        String v
    }

    private static boolean waitFor(long ms, Closure cond) {
        long end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond.call()) return true
            Thread.sleep(10)
        }
        return cond.call() as boolean
    }

    // ------------------------------------------------------------------ actors

    def "A1: a STOP directive raised by a failing handler stops the actor and fails the waiting ask at once"() {
        given:
        def system = new ActorSystem('h121-a1')
        def actor = system.actor {
            name 'stopper'
            supervisionStrategy SupervisionStrategy.stopAlways()
            onMessage { msg, ctx -> throw new IllegalStateException('boom') }
        }

        when:
        long t0 = System.currentTimeMillis()
        Throwable seen = null
        try {
            actor.askSync('x', Duration.ofSeconds(4))
        } catch (Throwable t) {
            seen = t
        }
        long took = System.currentTimeMillis() - t0

        then: 'the caller sees the handler failure, not a timeout'
        seen instanceof ExecutionException
        seen.cause instanceof IllegalStateException
        took < 2500

        and: 'the actor really terminated: its own loop thread did not wait on itself'
        waitFor(2500) { actor.isTerminated() }
    }

    def "A3: stop() processes every message already in the mailbox"() {
        given:
        def system = new ActorSystem('h121-a3')
        def done = new CopyOnWriteArrayList()
        def actor = system.actor('drainer') { msg, ctx ->
            Thread.sleep(40)
            done << msg
        }

        when:
        (1..6).each { actor.tell(it) }
        actor.stop()

        then:
        done == [1, 2, 3, 4, 5, 6]
    }

    def "A3: an ask still queued when the actor is force-stopped fails at once and does not wait for its timeout"() {
        given:
        def system = new ActorSystem('h121-a3b')
        def gate = new CountDownLatch(1)
        def started = new CountDownLatch(1)
        def actor = system.actor('blocked') { msg, ctx ->
            if (msg == 'block') {
                started.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
            'ok'
        }
        actor.tell('block')
        started.await(2, TimeUnit.SECONDS)
        def outcome = new AtomicReference()
        Thread.start {
            try {
                outcome.set(actor.askSync('queued', Duration.ofSeconds(4)))
            } catch (Throwable e) {
                outcome.set(e)
            }
        }
        Thread.sleep(200)

        when:
        actor.stopNow()

        then:
        waitFor(1500) { outcome.get() != null }
        outcome.get() instanceof ExecutionException

        cleanup:
        gate.countDown()
    }

    def "A4: a message that happens to be the text __STOP__ does not kill the mailbox loop"() {
        given:
        def system = new ActorSystem('h121-a4')
        def actor = system.actor('echo') { msg, ctx -> 'pong' }

        when:
        actor.tell('__STOP__')

        then:
        actor.askSync('ping', Duration.ofSeconds(2)) == 'pong'

        cleanup:
        system.shutdown()
    }

    // ------------------------------------------------------------------ agents

    def "G1: an agent's state version advances on updates and not on reads"() {
        given:
        def agent = AgentFactory.create([count: 0])
        long v0 = agent.stateVersion

        when:
        3.times { agent.value }
        long afterReads = agent.stateVersion
        agent.sendAndGet({ count++ }, 2)
        agent.sendAndGet({ count++ }, 2)

        then:
        afterReads == v0
        agent.stateVersion == v0 + 2

        cleanup:
        agent.shutdown()
    }

    // ------------------------------------------------------------------ serialisation

    def "S1: JavaSerializer refuses to deserialise a class that is not on its allow-list"() {
        given:
        def ser = new JavaSerializer()
        byte[] bytes = ser.serialize(new Payload(v: 'x'))

        when:
        ser.deserialize(bytes)

        then:
        thrown(SerializationException)
    }

    def "S1: JavaSerializer accepts JDK value types by default and application classes that were allowed"() {
        given:
        def plain = new JavaSerializer()
        def allowing = new JavaSerializer(['org.softwood.'])

        expect:
        plain.deserialize(plain.serialize([a: 1, b: ['x', 2L]])) == [a: 1, b: ['x', 2L]]
        allowing.deserialize(allowing.serialize(new Payload(v: 'x'))).v == 'x'
    }

    // ------------------------------------------------------------------ cluster

    def "C1: HazelcastManager builds its configuration from a HazelcastSecurityConfig"() {
        given:
        def security = HazelcastSecurityConfig.builder()
                .disableMulticast(true)
                .allowedMembers(['10.0.0.1:5701', '10.0.0.2:5701'])
                .bindAddress('10.0.0.1')
                .port(5800)
                .portAutoIncrement(false)
                .build()

        when:
        Config cfg = HazelcastManager.buildConfig([enabled: true, cluster: [name: 'h121']], security)
        def net = cfg.networkConfig

        then:
        cfg.clusterName == 'h121'
        !net.join.multicastConfig.enabled
        net.join.tcpIpConfig.enabled
        (net.join.tcpIpConfig.members as Set) == (['10.0.0.1:5701', '10.0.0.2:5701'] as Set)
        net.port == 5800
        !net.portAutoIncrement
        net.interfaces.enabled
        net.interfaces.interfaces.contains('10.0.0.1')
    }

    def "C1: with a security config and no member list the manager refuses, it does not fall back to multicast"() {
        given:
        def security = HazelcastSecurityConfig.builder().disableMulticast(true).build()

        when:
        HazelcastManager.buildConfig([enabled: true], security)

        then:
        thrown(IllegalStateException)
    }

    def "C1: member authentication asked for in the security config is switched on in the Hazelcast config"() {
        given:
        def security = HazelcastSecurityConfig.builder()
                .disableMulticast(true)
                .allowedMembers(['10.0.0.1:5701'])
                .authenticationEnabled(true)
                .clusterUsername('cluster-member')
                .clusterPassword('Str0ng!Passw0rd#2026')
                .build()

        when:
        Config cfg = HazelcastManager.buildConfig([enabled: true], security)

        then:
        cfg.securityConfig.enabled
        cfg.securityConfig.memberRealm != null
    }

    def "C1: without a security config the configuration is what 1.2.0 built"() {
        when:
        Config cfg = HazelcastManager.buildConfig([enabled: true, port: 5711], null)

        then:
        cfg.clusterName == 'taskgraph-cluster'
        cfg.networkConfig.port == 5711
        cfg.networkConfig.portAutoIncrement
        cfg.networkConfig.join.multicastConfig.enabled
    }

    // ------------------------------------------------------------------ configuration

    def "F1: ConfigLoader reads config files from an external directory"() {
        given:
        def dir = Files.createTempDirectory('h121cfg')
        new File(dir.toFile(), 'config.properties').text = 'h121.fromDir=yes\n'

        expect:
        ConfigLoader.load(externalDirs: [dir.toString()]).config['h121.fromDir'] == 'yes'

        cleanup:
        dir.toFile().deleteDir()
    }

    def "F1: ConfigLoader reads the file named by -Dapp.config and does not turn that property into a setting"() {
        given:
        def file = Files.createTempFile('h121', '.properties')
        file.toFile().text = 'h121.fromSysProp=yes\n'
        System.setProperty('app.config', file.toString())

        when:
        def result = ConfigLoader.load()

        then:
        result.config['h121.fromSysProp'] == 'yes'
        !result.config.containsKey('config')

        cleanup:
        System.clearProperty('app.config')
        Files.deleteIfExists(file)
    }

    // ------------------------------------------------------------------ TaskGraph binding

    def "M2: TaskBinding.set(key, null) removes the key and does not throw"() {
        given:
        def binding = new TaskBinding()
        binding.set('k', 'v')

        when:
        binding.set('k', null)
        binding['other'] = null

        then:
        noExceptionThrown()
        !binding.has('k')
        !binding.has('other')
    }

    // ------------------------------------------------------------------ timers and promises

    def "DF8: orTimeout releases its timer when the promise completes"() {
        given:
        def scheduler = ConcurrentPool.getSharedScheduledExecutor() as ScheduledThreadPoolExecutor
        int before = scheduler.queue.size()

        when:
        def promises = (1..200).collect {
            Promises.newPromise(PromiseImplementation.DATAFLOW).orTimeout(1, TimeUnit.HOURS)
        }
        promises.each { it.accept(1) }

        then:
        waitFor(3000) { scheduler.queue.size() <= before + 5 }
    }

    def "P3: a pool timeout timer is released when its task finishes in time"() {
        given:
        def scheduler = ConcurrentPool.getSharedScheduledExecutor() as ScheduledThreadPoolExecutor
        int before = scheduler.queue.size()
        def pool = new ConcurrentPool('h121-timers')

        when:
        (1..100).collect { pool.executeWithTimeout({ 1 }, 1, TimeUnit.HOURS) }*.get()

        then:
        waitFor(3000) { scheduler.queue.size() <= before + 5 }
    }

    def "DF14: a promise made from a failed CompletableFuture fails with the real cause"() {
        given:
        def future = CompletableFuture.supplyAsync { throw new IllegalArgumentException('bad') }
        def seen = new AtomicReference()

        when:
        Promises.from(future).onError { seen.set(it) }

        then:
        waitFor(2000) { seen.get() != null }
        seen.get() instanceof IllegalArgumentException
    }
}
