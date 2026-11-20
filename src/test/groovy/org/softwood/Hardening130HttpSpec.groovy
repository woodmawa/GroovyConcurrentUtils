package org.softwood

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.transform.CompileDynamic
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.HttpMethod
import org.softwood.dag.task.HttpTaskSecurityConfig
import spock.lang.Specification
import spock.lang.Timeout

import java.time.Duration

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

/**
 * GCU 1.3.0, WP4: the HttpTask guard (decision D3, brief gcu/brief-core-hardening-2026-11-18).
 *
 * Public hosts are allowed; private networks are blocked; a local address is blocked unless the flow
 * names its host and port; cloud metadata is always blocked; every redirect hop is checked again.
 * Each feature was run red against 1.2.2 before the guard was wired in.
 */
@CompileDynamic
@Timeout(60)
class Hardening130HttpSpec extends Specification {

    WireMockServer a
    WireMockServer b

    def setup() {
        a = new WireMockServer(wireMockConfig().dynamicPort())
        b = new WireMockServer(wireMockConfig().dynamicPort())
        a.start()
        b.start()
        a.stubFor(get(urlEqualTo('/data')).willReturn(aResponse().withStatus(200).withBody('from-a')))
        b.stubFor(get(urlEqualTo('/secret')).willReturn(aResponse().withStatus(200).withBody('from-b')))
    }

    def cleanup() {
        a?.stop()
        b?.stop()
    }

    private static Throwable failureOf(Closure work) {
        try {
            work.call()
            return null
        } catch (Throwable t) {
            return t
        }
    }

    private static boolean causedBy(Throwable t, Class type) {
        while (t != null) {
            if (type.isInstance(t)) return true
            t = t.cause
        }
        return false
    }

    private static String messages(Throwable t) {
        def parts = []
        while (t != null) {
            parts << String.valueOf(t.message)
            t = t.cause
        }
        return parts.join(' | ')
    }

    // ------------------------------------------------------------------ local addresses

    def "a local address is refused, and not contacted, unless the flow names its host and port"() {
        given:
        int port = a.port()
        def graph = TaskGraph.build {
            httpTask('call') {
                url "http://localhost:${port}/data"
                timeout Duration.ofSeconds(5)
            }
        }

        when:
        def failure = failureOf { graph.run().get() }

        then:
        causedBy(failure, SecurityException)
        a.getAllServeEvents().size() == 0
    }

    def "allowLocal on the task lets it call the named local host and port"() {
        given:
        int port = a.port()
        def graph = TaskGraph.build {
            httpTask('call') {
                allowLocal "localhost:${port}"
                url "http://localhost:${port}/data"
            }
        }

        expect:
        graph.run().get().body == 'from-a'
    }

    def "allowLocal on the graph covers every HttpTask in it, by loopback name or address"() {
        given:
        int port = a.port()
        def graph = TaskGraph.build {
            allowLocal "localhost:${port}"
            httpTask('call') {
                url "http://127.0.0.1:${port}/data"
            }
        }

        expect:
        graph.run().get().body == 'from-a'
    }

    def "allowLocal names a port: another local port stays refused"() {
        given:
        int portA = a.port()
        int portB = b.port()
        def graph = TaskGraph.build {
            httpTask('call') {
                allowLocal "localhost:${portA}"
                url "http://localhost:${portB}/secret"
            }
        }

        when:
        def failure = failureOf { graph.run().get() }

        then:
        causedBy(failure, SecurityException)
        b.getAllServeEvents().size() == 0
    }

    def "trusted true switches the guard off for that task"() {
        given:
        int port = a.port()
        def graph = TaskGraph.build {
            httpTask('call') {
                trusted true
                url "http://localhost:${port}/data"
            }
        }

        expect:
        graph.run().get().body == 'from-a'
    }

    // ------------------------------------------------------------------ redirects

    def "a redirect to a local port the flow did not name is refused and never reaches it"() {
        given:
        int portA = a.port()
        int portB = b.port()
        a.stubFor(get(urlEqualTo('/hop')).willReturn(
                aResponse().withStatus(302).withHeader('Location', "http://127.0.0.1:${portB}/secret")))
        def graph = TaskGraph.build {
            httpTask('call') {
                allowLocal "localhost:${portA}"
                url "http://localhost:${portA}/hop"
            }
        }

        when:
        def failure = failureOf { graph.run().get() }

        then:
        causedBy(failure, SecurityException)
        b.getAllServeEvents().size() == 0
    }

    def "a redirect to another allowed origin is followed, without the first origin's credentials"() {
        given:
        int portA = a.port()
        int portB = b.port()
        a.stubFor(get(urlEqualTo('/hop')).willReturn(
                aResponse().withStatus(302).withHeader('Location', "http://127.0.0.1:${portB}/secret")))
        def graph = TaskGraph.build {
            httpTask('call') {
                allowLocal "localhost:${portA}", "localhost:${portB}"
                url "http://localhost:${portA}/hop"
                auth { bearer 'token-for-a' }
            }
        }

        when:
        def response = graph.run().get()
        def atA = a.getAllServeEvents()
        def atB = b.getAllServeEvents()

        then:
        response.body == 'from-b'
        atA.size() == 1
        atA[0].request.getHeader('Authorization') == 'Bearer token-for-a'
        atB.size() == 1
        !atB[0].request.containsHeader('Authorization')
    }

    def "five redirects are followed and a sixth fails the task"() {
        given:
        int port = a.port()
        (1..6).each { int i ->
            String next = (i == 1) ? '/data' : "/r${i - 1}"
            a.stubFor(get(urlEqualTo("/r${i}")).willReturn(
                    aResponse().withStatus(302).withHeader('Location', next)))
        }
        def build = { String start ->
            TaskGraph.build {
                httpTask('call') {
                    allowLocal "localhost:${port}"
                    url "http://localhost:${port}${start}"
                }
            }
        }

        when:
        def five = build('/r5').run().get()
        def six = failureOf { build('/r6').run().get() }

        then:
        five.statusCode == 200
        five.body == 'from-a'
        six != null
        messages(six).toLowerCase().contains('redirect')
    }

    def "a POST answered with 303 is repeated as a GET to the new location"() {
        given:
        int port = a.port()
        a.stubFor(post(urlEqualTo('/submit')).willReturn(
                aResponse().withStatus(303).withHeader('Location', '/data')))
        def graph = TaskGraph.build {
            httpTask('call') {
                allowLocal "localhost:${port}"
                url "http://localhost:${port}/submit"
                method HttpMethod.POST
                body 'payload'
            }
        }

        expect:
        graph.run().get().body == 'from-a'
    }

    // ------------------------------------------------------------------ private networks and metadata

    def "a private network address is refused before any connection is tried"() {
        given:
        def graph = TaskGraph.build {
            httpTask('call') {
                url 'http://10.255.255.1:81/x'
                timeout Duration.ofSeconds(4)
            }
        }

        when:
        long t0 = System.currentTimeMillis()
        def failure = failureOf { graph.run().get() }
        long took = System.currentTimeMillis() - t0

        then:
        causedBy(failure, SecurityException)
        took < 2500
    }

    def "a cloud metadata address is refused even when the flow names it"() {
        given:
        def graph = TaskGraph.build {
            httpTask('call') {
                allowLocal '169.254.169.254:80'
                url 'http://169.254.169.254/latest/meta-data/'
                timeout Duration.ofSeconds(4)
            }
        }

        when:
        def failure = failureOf { graph.run().get() }

        then:
        causedBy(failure, SecurityException)
    }

    // ------------------------------------------------------------------ the policy object

    def "a wildcard host in the allow-list matches subdomains only"() {
        given:
        def policy = HttpTaskSecurityConfig.builder()
                .urlWhitelistEnabled(true)
                .allowedHosts(['*.invalid'])
                .allowedSchemes(['https'])
                .build()

        when:
        policy.validateUrl('https://evilinvalid/x')

        then:
        thrown(SecurityException)
    }

    def "allowLocal needs a host and a port"() {
        when:
        TaskGraph.build {
            httpTask('call') {
                allowLocal 'localhost'
                url 'http://localhost/x'
            }
        }

        then:
        thrown(IllegalArgumentException)
    }
}
