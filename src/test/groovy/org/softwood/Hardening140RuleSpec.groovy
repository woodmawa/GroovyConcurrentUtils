package org.softwood

import groovy.transform.CompileDynamic
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.ControlAction
import org.softwood.dag.task.ControlTask
import org.softwood.dag.task.DefaultTaskEventDispatcher
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path

/**
 * GCU 1.4.0, WP3 step 1: the rule profile on ControlTask (decision D2, brief gcu/brief-core-hardening-2026-11-18).
 *
 * A ControlTask rule condition is one expression over input, globals and memory. Anything else is
 * refused when the graph is validated, before any task runs. A condition that throws fails the node.
 * `trusted true` on the task gives the old unrestricted evaluation, and is logged.
 *
 * Before 1.4.0 a condition was any Groovy at all, and one that threw was read as false.
 * The new classes are reached by name so that this spec compiles, and runs red, on 1.3.0.
 */
@CompileDynamic
@Timeout(60)
class Hardening140RuleSpec extends Specification {

    @TempDir
    Path dir

    static class Assessment {
        BigDecimal confidence = 0.9G
        String riskLevel = 'low'
        File report
    }

    static final Map<String, Object> INPUT = [
            confidence: 0.6G,
            riskLevel : 'high',
            healthy   : true,
            tags      : ['a', 'b'],
            name      : ' Alpha ',
            count     : 3,
            nested    : [level: 2],
            items     : [[n: 1], [n: 2]]
    ]

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

    private static ControlAction actionOf(Object result) {
        if (result instanceof ControlAction) return result
        if (result instanceof Map) return result.values().find { it instanceof ControlAction } as ControlAction
        if (result instanceof Collection) return result.find { it instanceof ControlAction } as ControlAction
        return null
    }

    /** One ControlTask, 'decide', whose input is the value given. */
    private static TaskGraph graphWith(List<Map<String, Object>> rules, Object input = INPUT, Closure tweak = null) {
        TaskGraph graph = TaskGraph.build { }
        graph.ctx.globals.set('score', 0.4G)
        graph.ctx.globals.set('__workingMemory', [resultsByNode: [llm: [value: input]]])
        ControlTask task = new ControlTask('decide', 'decide', graph.ctx)
        task.eventDispatcher = new DefaultTaskEventDispatcher(graph)
        task.rules = rules
        task.inputNodeId = 'llm'
        if (tweak) tweak.call(task)
        graph.addTask(task)
        return graph
    }

    private static List<Map<String, Object>> hitWhen(String condition) {
        [[when: condition, action: 'ROUTE', target: 'hit']] as List<Map<String, Object>>
    }

    private static ControlAction run(TaskGraph graph) {
        actionOf(graph.start().get())
    }

    // ------------------------------------------------------------------ refused before anything runs

    def "a condition outside the rule profile is refused when the graph is validated: #rule"() {
        given:
        def graph = graphWith(hitWhen(rule))

        when:
        def failure = failureOf { graph.validate() }

        then:
        failure instanceof IllegalStateException
        failure.message.contains("'decide'")

        where:
        rule << [
                '"hostname".execute() != null',
                'System.getenv("PATH") != null',
                'new File(".").exists()',
                'new ProcessBuilder("hostname") != null',
                'Class.forName("java.lang.Runtime") != null',
                'java.lang.Runtime.runtime != null',
                'input.class.name != null',
                'input.metaClass != null',
                'input.getClass() != null',
                'input."${\'cl\' + \'ass\'}" != null',
                'input.properties != null',
                'this.class != null',
                'Eval.me("1") == 1',
                '{ -> true }()',
                'input.tags.each { it } != null',
                'input.tags.collect { it }.size() == 2',
                'input.count = 5',
                'input.count++ > 0',
                'def x = 1',
                'true; false',
                'while (true) { }',
                'input.tags*.size() != null',
                'input.&size != null',
                'input.@count == 3',
                'input instanceof Map',
                '("http://example.com" as URL) != null',
                'input.name.bytes != null && input.name.getBytes() != null',
                'input.tags.remove(0) != null',
                'Math.class != null',
                'memory.clear() == null',
                'true' + (' && true' * 400)
        ]
    }

    def "an escape written as a rule does not run"() {
        given:
        Path marker = dir.resolve('escaped.txt')
        String path = marker.toString().replace('\\', '/')
        def graph = graphWith(hitWhen("new File('${path}').createNewFile()".toString()))

        when:
        def failure = failureOf { graph.start().get() }

        then:
        failure instanceof IllegalStateException
        !Files.exists(marker)
    }

    def "a condition that does not parse fails validation and names the task and the rule"() {
        given:
        def graph = graphWith(hitWhen('input.confidence >'))

        when:
        def failure = failureOf { graph.validate() }

        then:
        failure instanceof IllegalStateException
        failure.message.contains("'decide'")
        failure.message.contains('input.confidence >')
    }

    def "a condition that names something it is not given fails validation"() {
        given:
        def graph = graphWith(hitWhen('inptu.confidence < 0.5'))

        when:
        def failure = failureOf { graph.validate() }

        then:
        failure instanceof IllegalStateException
        failure.message.contains('inptu')
    }

    def "every bad rule in a task is reported, not only the first"() {
        given:
        def graph = graphWith([
                [when: 'input.confidence >', action: 'CONTINUE'],
                [when: 'input.count == 3', action: 'CONTINUE'],
                [when: 'System.exit(1)', action: 'ABORT']
        ] as List<Map<String, Object>>)

        when:
        def failure = failureOf { graph.validate() }

        then:
        failure instanceof IllegalStateException
        failure.message.contains('input.confidence >')
        failure.message.contains('System.exit(1)')
        !failure.message.contains('input.count == 3')
    }

    // ------------------------------------------------------------------ a throwing condition

    def "a condition that throws fails the node instead of reading as false"() {
        given:
        def graph = graphWith(hitWhen('input.missing.size() > 0'))

        when:
        graph.validate()

        then:
        noExceptionThrown()

        when:
        def failure = failureOf { graph.start().get() }

        then:
        failure != null
        messages(failure).contains('input.missing.size() > 0')
    }

    // ------------------------------------------------------------------ refused while running

    def "a rule cannot read through a File it finds in its input"() {
        given:
        Path secret = dir.resolve('secret.txt')
        Files.writeString(secret, 'secret')
        def graph = graphWith(hitWhen("input.report.text == 'secret'"), new Assessment(report: secret.toFile()))

        when:
        def failure = failureOf { graph.start().get() }

        then:
        causedBy(failure, SecurityException)
    }

    def "a rule cannot reach the class of an object by indexing it"() {
        given:
        def graph = graphWith(hitWhen("input['class'] != null"), new Assessment())

        when:
        def failure = failureOf { graph.start().get() }

        then:
        causedBy(failure, SecurityException)
    }

    // ------------------------------------------------------------------ what a rule can say

    def "a condition inside the profile evaluates: #rule"() {
        given:
        def graph = graphWith(hitWhen(rule))

        when:
        ControlAction action = run(graph)

        then:
        action != null
        action.type == (matches ? ControlAction.Type.ROUTE : ControlAction.Type.ESCALATE)

        where:
        rule                                                        | matches
        'input.confidence < 0.75'                                   | true
        'input.confidence > 0.75'                                   | false
        "input.riskLevel == 'high'"                                 | true
        'input.healthy == true'                                     | true
        "input.confidence < 0.75 && input.riskLevel != 'low'"       | true
        '!(input.healthy) || input.count >= 3'                      | true
        '!input.healthy'                                            | false
        'input.count + 2 * 2 == 7'                                  | true
        'input.count % 2 == 1'                                      | true
        '-input.count < 0'                                          | true
        'input.count / 2 == 1.5'                                    | true
        'input.tags.size() == 2'                                    | true
        "input.tags.contains('b')"                                  | true
        "'a' in input.tags"                                         | true
        "'z' in input.tags"                                         | false
        "input.tags[0] == 'a'"                                      | true
        "input.tags[-1] == 'b'"                                     | true
        'input.tags.isEmpty()'                                      | false
        "input['riskLevel'].startsWith('hi')"                       | true
        "input.riskLevel.endsWith('gh') && input.riskLevel.matches('h.*')" | true
        "input.name.trim().toLowerCase() == 'alpha'"                | true
        "input.name.trim().toUpperCase() == 'ALPHA'"                | true
        'input.nested.level == 2'                                   | true
        'input.missing?.level == null'                              | true
        '(input.missing ?: 5) == 5'                                 | true
        "(input.count > 2 ? 'big' : 'small') == 'big'"              | true
        'Math.max(input.count, 10) == 10'                           | true
        'Math.abs(-2) == 2 && Math.PI > 3'                          | true
        'input.riskLevel ==~ /h.*h/'                                | true
        'input.riskLevel =~ /zz/'                                   | false
        '"${input.riskLevel}-${input.count}" == \'high-3\''         | true
        "(input.count as String) == '3'"                            | true
        "input.confidence.toString() == '0.6'"                      | true
        'input.items.n == [1, 2]'                                   | true
        "[low: 1, high: 2][input.riskLevel] == 2"                   | true
        'input.containsKey("nested")'                               | true
        'globals.score < 0.5'                                       | true
        "globals.containsKey('score')"                              | true
        "globals['score'] == 0.4"                                   | true
        "memory.resultsByNode.containsKey('llm')"                   | true
    }

    def "a rule reads the properties of a plain object"() {
        expect:
        run(graphWith(hitWhen("input.confidence > 0.75 && input.riskLevel.toUpperCase() == 'LOW'"), new Assessment())).type ==
                ControlAction.Type.ROUTE
    }

    def "the first matching rule decides, and the default applies when none match"() {
        given:
        def rules = [
                [when: 'input.count > 10', action: 'ABORT', reason: 'too many'],
                [when: 'input.count > 2', action: 'ROUTE', target: 'second'],
                [when: 'input.count > 1', action: 'ROUTE', target: 'third']
        ] as List<Map<String, Object>>

        when:
        ControlAction first = run(graphWith(rules))
        ControlAction none = run(graphWith(hitWhen('input.count > 99'), INPUT) { ControlTask t ->
            t.defaultRule = [action: 'CONTINUE']
        })

        then:
        first.type == ControlAction.Type.ROUTE
        first.target == 'second'
        none.type == ControlAction.Type.CONTINUE
    }

    def "a rule cannot change globals"() {
        given:
        def graph = graphWith(hitWhen("globals.score < 0.5"))

        when:
        run(graph)

        then:
        graph.ctx.globals.get('score') == 0.4G
    }

    // ------------------------------------------------------------------ compiled once

    def "a rule is compiled once and the compiled form is reused"() {
        given:
        Class compiler = Class.forName('org.softwood.dag.script.RestrictedCompiler')

        when:
        def first = compiler.compileRule('input.count == 3 && input.healthy')
        def second = compiler.compileRule('input.count == 3 && input.healthy')

        then:
        first.is(second)
        first.test([input: INPUT, globals: [:], memory: [:]])
    }

    // ------------------------------------------------------------------ trusted true

    def "trusted true evaluates a condition as unrestricted Groovy"() {
        given:
        def graph = graphWith(hitWhen('System.getProperty("user.dir") != null && input.tags.collect { it }.size() == 2'), INPUT) {
            ControlTask t -> t.trusted(true)
        }

        expect:
        run(graph).type == ControlAction.Type.ROUTE
    }

    def "a trusted condition that throws still fails the node"() {
        given:
        def graph = graphWith(hitWhen('input.missing.size() > 0'), INPUT) { ControlTask t -> t.trusted(true) }

        when:
        def failure = failureOf { graph.start().get() }

        then:
        failure != null
        messages(failure).contains('input.missing.size() > 0')
    }

    def "a trusted condition that does not compile still fails validation"() {
        given:
        def graph = graphWith(hitWhen('input.confidence >'), INPUT) { ControlTask t -> t.trusted(true) }

        when:
        def failure = failureOf { graph.validate() }

        then:
        failure instanceof IllegalStateException
        failure.message.contains("'decide'")
    }
}
