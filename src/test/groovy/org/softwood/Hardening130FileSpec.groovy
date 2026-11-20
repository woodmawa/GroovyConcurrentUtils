package org.softwood

import groovy.transform.CompileDynamic
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.FileTaskSecurityConfig
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path

/**
 * GCU 1.3.0: the FileTask default (decision D9, brief gcu/brief-core-hardening-2026-11-18).
 *
 * A FileTask with no security configuration runs under the strict limits, confined to the graph's
 * working directory (the JVM's if the graph names none) plus any directories the flow names.
 * It used to run with no limits at all. Each feature was run red against 1.2.2.
 */
@CompileDynamic
@Timeout(60)
class Hardening130FileSpec extends Specification {

    @TempDir
    Path outside      // a directory that is not under the JVM's working directory

    def setup() {
        Files.writeString(outside.resolve('a.txt'), 'alpha')
        Files.writeString(outside.resolve('b.txt'), 'beta')
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

    private List<String> namesFrom(TaskGraph graph) {
        def result = graph.start().get()
        return (result.data as List<File>)*.name.sort()
    }

    def "a FileTask with no security configuration cannot read outside the working directory"() {
        given:
        String dir = outside.toString()
        def graph = TaskGraph.build {
            fileTask('read') {
                sources { directory(dir) { pattern '*.txt' } }
                execute { ctx -> ctx.files }
            }
        }

        when:
        def failure = failureOf { graph.start().get() }

        then:
        causedBy(failure, SecurityException)
    }

    def "the graph's working directory is the default scope"() {
        given:
        String dir = outside.toString()
        def graph = TaskGraph.build {
            workingDirectory dir
            fileTask('read') {
                sources { directory(dir) { pattern '*.txt' } }
                execute { ctx -> ctx.files }
            }
        }

        expect:
        namesFrom(graph) == ['a.txt', 'b.txt']
    }

    def "allowedDirectories on the graph widens the scope for every FileTask in it"() {
        given:
        Path dirPath = outside
        String dir = outside.toString()
        def graph = TaskGraph.build {
            allowedDirectories dirPath
            fileTask('read') {
                sources { directory(dir) { pattern '*.txt' } }
                execute { ctx -> ctx.files }
            }
        }

        expect:
        namesFrom(graph) == ['a.txt', 'b.txt']
    }

    def "allowedDirectories on the task widens the scope for that task"() {
        given:
        String dir = outside.toString()
        def graph = TaskGraph.build {
            fileTask('read') {
                allowedDirectories dir
                sources { directory(dir) { pattern '*.txt' } }
                execute { ctx -> ctx.files }
            }
        }

        expect:
        namesFrom(graph) == ['a.txt', 'b.txt']
    }

    def "trusted true removes the limits for that task"() {
        given:
        String dir = outside.toString()
        def graph = TaskGraph.build {
            fileTask('read') {
                trusted true
                sources { directory(dir) { pattern '*.txt' } }
                execute { ctx -> ctx.files }
            }
        }

        expect:
        namesFrom(graph) == ['a.txt', 'b.txt']
    }

    def "permissive() is available whatever the ENVIRONMENT setting: it has to be named, that is the control"() {
        given:
        String before = System.getProperty('environment')
        System.setProperty('environment', 'production')

        when:
        def config = FileTaskSecurityConfig.permissive()

        then:
        noExceptionThrown()
        !config.strictMode

        cleanup:
        if (before == null) System.clearProperty('environment') else System.setProperty('environment', before)
    }
}
