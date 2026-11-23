package org.softwood

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.transform.CompileDynamic
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.ControlAction
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.any
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

/**
 * GCU 1.4.1: the items carried forward from the 1.4.0 "not verified" list, and one hole found while
 * designing allow 'sql'.
 *
 * - ScriptFiles: symbolic links and the size limit.
 * - ScriptHttp: post, put, delete and request headers.
 * - A ControlTask and a ScriptTask wired after a real upstream task.
 * - A capability object exposes only its methods. Before 1.4.1 a script could read a capability's
 *   private fields through Groovy property syntax: `http.allowLocal` is the list of local services
 *   the graph named, and a script could add to it.
 */
@CompileDynamic
@Timeout(60)
class Hardening141CarriedSpec extends Specification {

    @TempDir
    Path dir

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

    private static Object run(TaskGraph graph) {
        graph.start().get()
    }

    private static ControlAction actionOf(Object result) {
        if (result instanceof ControlAction) return result
        if (result instanceof Map) return result.values().find { it instanceof ControlAction } as ControlAction
        if (result instanceof Collection) return result.find { it instanceof ControlAction } as ControlAction
        return null
    }

    static boolean canLink() {
        Path tmp = Files.createTempDirectory('gcu-link')
        try {
            Path target = Files.writeString(tmp.resolve('t.txt'), 't')
            Files.createSymbolicLink(tmp.resolve('l.txt'), target)
            return true
        } catch (Exception ignored) {
            return false
        } finally {
            tmp.toFile().deleteDir()
        }
    }

    // ------------------------------------------------------------------ ScriptFiles

    @Requires({ Hardening141CarriedSpec.canLink() })
    def "files refuses a symbolic link, even one that points inside the working directory: #action"() {
        given:
        Path target = Files.writeString(dir.resolve('real.txt'), 'real')
        Files.createSymbolicLink(dir.resolve('link.txt'), target)
        String where = dir.toString()
        String code = action
        def graph = TaskGraph.build {
            allow 'files'
            workingDirectory where
            scriptTask('s') { script code }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException)
        Files.readString(target) == 'real'

        where:
        action << ["files.read('link.txt')", "files.write('link.txt', 'changed')", "files.append('link.txt', '!')"]
    }

    def "files refuses to read a file over the size limit, or to grow one past it: #action"() {
        given:
        long limit = 100L * 1024 * 1024
        new RandomAccessFile(dir.resolve('big.bin').toFile(), 'rw').withCloseable { it.setLength(limit + 1) }
        new RandomAccessFile(dir.resolve('full.bin').toFile(), 'rw').withCloseable { it.setLength(limit) }
        String where = dir.toString()
        String code = action
        def graph = TaskGraph.build {
            allow 'files'
            workingDirectory where
            scriptTask('s') { script code }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException)
        Files.size(dir.resolve('full.bin')) == limit

        where:
        action << ["files.read('big.bin')", "files.size('big.bin')", "files.readLines('big.bin')", "files.append('full.bin', 'x')"]
    }

    // ------------------------------------------------------------------ ScriptHttp

    def "http sends post, put and delete with their bodies and headers, and reads response headers"() {
        given:
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort())
        server.start()
        server.stubFor(any(urlEqualTo('/thing')).willReturn(aResponse().withStatus(201).withHeader('X-Echo', 'seen').withBody('ok')))
        int port = server.port()
        String local = "localhost:${port}".toString()
        String base = "http://localhost:${port}/thing".toString()
        String code = """def a = http.post('${base}', '{"n":1}', ['Content-Type': 'application/json', 'X-Token': 'p'])
def b = http.put('${base}', 'replaced', ['X-Token': 'u'])
def c = http.delete('${base}', ['X-Token': 'd'])
[a.status, a.body, a.headers['x-echo'], b.status, c.status]""".toString()

        when:
        def result = run(TaskGraph.build {
            allow 'http'
            allowLocal local
            scriptTask('s') { script code }
        })

        then:
        result == [201, 'ok', 'seen', 201, 201]
        server.findAll(anyRequestedFor(urlEqualTo('/thing'))).collect { [it.method.value(), it.bodyAsString, it.getHeader('X-Token')] } ==
                [['POST', '{"n":1}', 'p'], ['PUT', 'replaced', 'u'], ['DELETE', '', 'd']]
        server.findAll(anyRequestedFor(urlEqualTo('/thing')).withHeader('Content-Type', equalTo('application/json'))).size() == 1

        cleanup:
        server?.stop()
    }

    def "http's post, put and delete are refused for an address the guard refuses: #method"() {
        given:
        String code = method == 'delete' ?
                "http.delete('http://169.254.169.254/x')" :
                "http.${method}('http://169.254.169.254/x', 'body')".toString()

        expect:
        causedBy(failureOf { run(TaskGraph.build { allow 'http'; scriptTask('s') { script code } }) }, SecurityException)

        where:
        method << ['post', 'put', 'delete']
    }

    // ------------------------------------------------------------------ a capability exposes only its methods

    def "a script cannot add a local service to http's allowance and then call it"() {
        given:
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort())
        server.start()
        server.stubFor(any(urlEqualTo('/secret')).willReturn(aResponse().withStatus(200).withBody('secret')))
        int port = server.port()
        String code = "http.allowLocal.add('localhost:${port}')\nhttp.get('http://localhost:${port}/secret').body".toString()

        when:
        def failure = failureOf { run(TaskGraph.build { allow 'http'; scriptTask('s') { script code } }) }

        then:
        failure != null
        server.findAll(anyRequestedFor(urlEqualTo('/secret'))).isEmpty()

        cleanup:
        server?.stop()
    }

    def "a script reaches nothing of a capability but its methods: #code"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'files', 'http'
            workingDirectory where
            scriptTask('s') { script code }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException) || failure != null

        where:
        code << [
                'files.config',
                'files.validator',
                'files.base',
                'files.@config',
                'files.config.maxFileSizeBytes = Long.MAX_VALUE',
                "files.getProperty('config')",
                "files.invokeMethod('checked', 'x')",
                "files.checked('x')",
                'files.properties',
                'http.allowLocal',
                'http.guard',
                'http.client',
                "http.send('GET', 'http://localhost:1/', null, [:])",
        ]
    }

    // ------------------------------------------------------------------ wired after a real upstream task

    def "a ScriptTask after a real upstream task receives the upstream result as prev"() {
        given:
        def graph = TaskGraph.build {
            serviceTask('up') {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { [items: [2, 3, 4]] } }
            }
            scriptTask('s') { script 'prev.items.sum() * 10' }
            dependsOn('s', 'up')
        }

        expect:
        run(graph) == 90
    }

    def "a ControlTask after a real upstream task decides on the upstream result"() {
        given:
        def build = { int score ->
            TaskGraph graph = TaskGraph.build {
                serviceTask('up') {
                    action { ctx, prev -> ctx.promiseFactory.executeAsync { [score: score] } }
                }
            }
            def decide = new org.softwood.dag.task.ControlTask('decide', 'decide', graph.ctx)
            decide.eventDispatcher = new org.softwood.dag.task.DefaultTaskEventDispatcher(graph)
            decide.rules = [[when: 'input.score > 5', action: 'ROUTE', target: 'hit']]
            graph.addTask(decide)
            def up = graph.tasks['up']
            up.addSuccessor(decide)
            decide.addPredecessor(up)
            graph
        }

        when:
        ControlAction high = actionOf(run(build(9)))
        ControlAction low = actionOf(run(build(1)))

        then:
        high.type == ControlAction.Type.ROUTE
        high.target == 'hit'
        low.type != ControlAction.Type.ROUTE
    }
}
