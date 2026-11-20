package org.softwood

import groovy.transform.CompileDynamic
import org.softwood.actor.ActorSystem
import org.softwood.actor.remote.http.HttpTransport
import org.softwood.actor.remote.rsocket.RSocketTransport
import org.softwood.dag.task.HttpTaskSecurityConfig
import org.softwood.security.AuthConfig
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import java.time.Duration
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * GCU 1.3.0, WP8: remote actor transports (decision D10, brief gcu/brief-core-hardening-2026-11-18).
 *
 * A server transport listens on the loopback address unless told otherwise; a wider address needs
 * authentication, or an explicit "insecure"; only actors marked remote can be reached from outside.
 * Each feature was run red against 1.2.2.
 */
@CompileDynamic
@Timeout(60)
class Hardening130RemotingSpec extends Specification {

    ActorSystem system
    List<Closeable> opened = []

    def setup() {
        system = new ActorSystem('h130')
        system.actor {
            name 'open'
            onMessage { msg, ctx -> ctx.reply("open: ${msg}".toString()) }
        }
        system.actor {
            name 'internal'
            onMessage { msg, ctx -> ctx.reply("internal: ${msg}".toString()) }
        }
    }

    def cleanup() {
        opened.each {
            try {
                it.close()
            } catch (Throwable ignored) {
            }
        }
        system?.close()
    }

    private static int freePort() {
        def socket = new ServerSocket(0)
        try {
            return socket.localPort
        } finally {
            socket.close()
        }
    }

    private static AuthConfig auth() {
        new AuthConfig(enabled: true, jwtSecret: 'h130-secret-h130-secret-h130-secret-0123')
    }

    private static Throwable failureOf(Closure work) {
        try {
            work.call()
            return null
        } catch (Throwable t) {
            return t
        }
    }

    private def newTransport(String kind, int port, AuthConfig authConfig = null) {
        def t = (kind == 'http')
                ? new HttpTransport(system, port, true, null, authConfig)
                : new RSocketTransport(system, port, true, null, authConfig)
        opened << t
        return t
    }

    def "the HTTP server listens on the loopback address unless told otherwise"() {
        given:
        def transport = newTransport('http', freePort())

        when:
        transport.start()
        InetAddress bound = transport.@server.address.address

        then:
        bound.isLoopbackAddress()
    }

    @Unroll
    def "a #kind server on a non-loopback address with authentication off refuses to start"() {
        given:
        def transport = newTransport(kind, freePort())

        when:
        transport.bindAddress = '0.0.0.0'
        transport.start()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('AuthConfig')
        e.message.contains('insecure')

        where:
        kind << ['http', 'rsocket']
    }

    @Unroll
    def "a #kind server on a non-loopback address starts with #how"() {
        given:
        def transport = newTransport(kind, freePort(), withAuth ? auth() : null)

        when:
        transport.bindAddress = '0.0.0.0'
        transport.insecure = insecure
        transport.start()

        then:
        noExceptionThrown()

        where:
        kind      | withAuth | insecure | how
        'http'    | true     | false    | 'authentication on'
        'http'    | false    | true     | 'insecure true'
        'rsocket' | true     | false    | 'authentication on'
        'rsocket' | false    | true     | 'insecure true'
    }

    @Unroll
    def "over #kind an actor marked remote is reachable and one that is not marked answers as if it did not exist"() {
        given:
        int port = freePort()
        def server = newTransport(kind, port)
        server.start()
        Thread.sleep(800)
        def client = (kind == 'http') ? new HttpTransport(null, 0, false) : new RSocketTransport(null, 0, false)
        opened << client
        client.start()
        String base = "${kind}://localhost:${port}/h130"

        when:
        def hidden = failureOf {
            client.ask("${base}/internal".toString(), 'hi', Duration.ofSeconds(5)).get(8, TimeUnit.SECONDS)
        }
        def missing = failureOf {
            client.ask("${base}/no-such-actor".toString(), 'hi', Duration.ofSeconds(5)).get(8, TimeUnit.SECONDS)
        }
        def marking = failureOf { system.markRemote('open') }
        def reachable = failureOf {
            assert client.ask("${base}/open".toString(), 'hi', Duration.ofSeconds(5)).get(8, TimeUnit.SECONDS) == 'open: hi'
        }

        then:
        hidden != null
        missing != null
        marking == null
        reachable == null
        String.valueOf(hidden.cause?.message ?: hidden.message).replace('internal', 'X') ==
                String.valueOf(missing.cause?.message ?: missing.message).replace('no-such-actor', 'X')

        where:
        kind << ['http', 'rsocket']
    }

    def "an actor is marked remote in its builder, or afterwards, and can be unmarked"() {
        given:
        system.actor {
            name 'built-remote'
            remote true
            onMessage { msg, ctx -> ctx.reply('x') }
        }

        expect:
        system.isRemote('built-remote')
        !system.isRemote('internal')

        when:
        system.markRemote('internal')

        then:
        system.isRemote('internal')

        when:
        system.markRemote('internal', false)

        then:
        !system.isRemote('internal')
    }

    def "the HTTP server's worker pool is bounded"() {
        given:
        def transport = newTransport('http', freePort())

        when:
        transport.start()
        def executor = transport.@server.executor

        then:
        executor instanceof ThreadPoolExecutor
        ((ThreadPoolExecutor) executor).maximumPoolSize <= 64
        ((ThreadPoolExecutor) executor).queue.remainingCapacity() < Integer.MAX_VALUE
    }

    def "HttpTaskSecurityConfig.permissive() is available whatever the ENVIRONMENT setting"() {
        given:
        String before = System.getProperty('environment')
        System.setProperty('environment', 'production')

        when:
        def config = HttpTaskSecurityConfig.permissive()

        then:
        noExceptionThrown()
        !config.blockPrivateNetworks

        cleanup:
        if (before == null) System.clearProperty('environment') else System.setProperty('environment', before)
    }
}
