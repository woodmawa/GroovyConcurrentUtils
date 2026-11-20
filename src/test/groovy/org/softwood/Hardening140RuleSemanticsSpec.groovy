package org.softwood

import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.Timeout

import java.time.LocalDate

/**
 * GCU 1.4.0, WP3 step 1: a rule inside the profile means what the same Groovy expression means.
 *
 * The rule profile evaluates a condition by walking its syntax tree instead of running it as Groovy.
 * This spec holds the two side by side: each expression is evaluated by the rule profile and by a
 * plain Groovy shell against the same values, and the two must agree: the same value of the same
 * type, or a failure in both. It is the guard that property access, indexing, null handling,
 * truth and number arithmetic keep their Groovy meaning.
 *
 * The compiler is reached by name so that the spec compiles on 1.3.0.
 */
@CompileDynamic
@Timeout(60)
class Hardening140RuleSemanticsSpec extends Specification {

    static class Address {
        String city = 'Ipswich'
        String postcode = null
    }

    static class Person {
        String name = 'Ann'
        int age = 41
        boolean active = true
        public String code = 'P1'
        Address address = new Address()
        List<Address> homes = [new Address(city: 'York'), new Address(city: 'Leeds')]

        String getDisplay() { "${name} (${age})".toString() }

        boolean isAdult() { age >= 18 }
    }

    static class Loose {
        String known = 'k'

        def propertyMissing(String name) { "missing:${name}".toString() }
    }

    enum Level { LOW, HIGH }

    static final Map<String, Object> INPUT = [
            person   : new Person(),
            loose    : new Loose(),
            level    : Level.HIGH,
            n        : 3,
            d        : 2.5G,
            dbl      : 1.5d,
            lng      : 10L,
            s        : 'Hello',
            empty    : '',
            nul      : null,
            list     : [3, 1, 2],
            names    : ['b', 'a'],
            people   : [[name: 'x', tags: ['p']], [name: 'y', tags: []]],
            nested   : [a: [b: [c: 42]]],
            arr      : [1, 2, 3] as int[],
            sarr     : ['x', 'y'] as String[],
            set      : ['q'] as Set,
            emptyList: [],
            emptyMap : [:],
            zero     : 0,
            t        : true,
            f        : false,
            expando  : new Expando(colour: 'red'),
            date     : LocalDate.of(2026, 11, 20),
            'odd key': 7,
            size     : 99,
            'class'  : 'k'
    ]

    static final Map<String, Object> VARIABLES = [
            input  : INPUT,
            globals: [score: 0.4G, flowId: 'f-1'],
            memory : [resultsByNode: [llm: [value: 1]]]
    ]

    /** [true, value] or [false, null] when the evaluation threw. A Matcher is read as whether it finds. */
    private static List outcome(Closure work) {
        try {
            def value = work.call()
            return [true, value instanceof java.util.regex.Matcher ? value.find() : value]
        } catch (Throwable ignored) {
            return [false, null]
        }
    }

    private static Class kind(Object value) {
        if (value == null) return null
        if (value instanceof CharSequence) return String
        if (value instanceof List) return List
        if (value instanceof Map) return Map
        return value.getClass()
    }

    def "the rule profile agrees with Groovy on: #expression"() {
        given:
        Class compiler = Class.forName('org.softwood.dag.script.RestrictedCompiler')

        when:
        List groovy = outcome { new GroovyShell(new Binding(new LinkedHashMap(VARIABLES))).evaluate(expression) }
        List rule = outcome { compiler.compileRule(expression).evaluate(VARIABLES) }

        then: 'both give a value, or both fail'
        rule[0] == groovy[0]

        and: 'the same value'
        rule[1] == groovy[1]

        and: 'of the same kind'
        kind(rule[1]) == kind(groovy[1])

        where:
        expression << [
                // ---- maps: a property is the entry, whatever its name
                'input.n',
                'input.nested.a.b.c',
                'input.nested.a.x',
                'input.nested.x?.y',
                'input.nested.x.y',
                "input.'odd key'",
                "input['odd key']",
                'input.size',
                'input.size()',
                "input['class']",
                'input.nul',
                'input.missing',
                'input.emptyMap.anything',
                'input.isEmpty()',
                "input.containsKey('nul')",
                'globals.score',
                "globals['flowId']",
                'memory.resultsByNode.llm.value',

                // ---- objects: getters, boolean getters, public fields, propertyMissing
                'input.person.name',
                'input.person.age',
                'input.person.active',
                'input.person.adult',
                'input.person.display',
                'input.person.code',
                'input.person.address.city',
                'input.person.address.postcode',
                'input.person.address.postcode?.size()',
                'input.person.address.postcode.size()',
                "input.person['name']",
                'input.person.nothing',
                'input.person.homes.city',
                'input.person.homes[0].city',
                'input.person.homes[-1].city',
                'input.person.homes[5]',
                'input.person.homes[5]?.city',
                'input.person.homes[5].city',
                'input.loose.known',
                'input.loose.whatever',
                'input.expando.colour',
                'input.expando.nothing',
                'input.level.toString()',
                "input.level == 'HIGH'",
                'input.level == input.level',
                'input.date.year',
                'input.date.monthValue',
                'input.date.toString()',
                'input.date.dayOfWeek.toString()',

                // ---- lists, sets, arrays
                'input.list.size()',
                'input.list.empty',
                'input.emptyList.empty',
                'input.list.isEmpty()',
                'input.list[0]',
                'input.list[-1]',
                'input.list[9]',
                'input.list.first',
                "input.names.contains('a')",
                'input.people.name',
                'input.people.tags',
                'input.people.name.size()',
                'input.people[1].tags.isEmpty()',
                'input.people[1].tags.empty',
                'input.people.missing',
                'input.set.size()',
                "input.set.contains('q')",
                'input.arr.length',
                'input.arr.size()',
                'input.arr[1]',
                'input.arr[-1]',
                'input.sarr[0]',
                'input.sarr.length',
                'input.list.toString()',
                'input.nested.toString()',

                // ---- text
                'input.s.size()',
                'input.s[0]',
                'input.s[-1]',
                'input.s.toLowerCase()',
                'input.s.toUpperCase()',
                'input.s.trim()',
                'input.s.empty',
                'input.empty.empty',
                'input.s.isEmpty()',
                "input.s.contains('ell')",
                "input.s.startsWith('He')",
                "input.s.endsWith('lo')",
                "input.s.matches('H.*')",
                'input.s.toString()',
                '"${input.s}!"',
                '"${input.s}!" == \'Hello!\'',
                '"n=${input.n} d=${input.d} nul=${input.nul} list=${input.list}"',
                '"${input.s}".size()',

                // ---- numbers
                'input.n + input.lng',
                'input.n * input.d',
                'input.n - input.dbl',
                'input.n / 2',
                'input.n / input.d',
                'input.lng / input.n',
                'input.n % 2',
                'input.lng % input.n',
                'input.n ** 2',
                '-input.d',
                '-input.n',
                '+input.n',
                'input.dbl + input.d',
                'input.n == 3.0',
                'input.n == input.lng',
                'input.lng > input.d',
                'input.d <=> input.dbl',
                'input.dbl <= 1.5',
                "input.n == '3'",
                'input.s + input.n',
                'input.s + input.nul',
                'input.n as String',
                'input.d as int',
                '(int) input.dbl',
                "'42' as Integer",
                'input.n as BigDecimal',
                'input.t as String',
                'input.d as Long',
                'input.n.toString()',
                'input.n + 2 * 3 - 1',
                '(input.n + 2) * 3',

                // ---- null
                'input.nul == null',
                'input.nul != null',
                'input.nul < 1',
                'input.nul > 1',
                'input.nul == input.missing',
                'input.nul <=> 1',
                'input.nul?.foo',
                'input.nul.foo',
                'input.nul.toString()',
                'input.nul?.toString()',
                'input.nul.size()',
                'input.nul + 1',
                'input.nul[0]',
                'input.nul?[0]',

                // ---- truth
                'input.empty ? 1 : 2',
                'input.zero ? 1 : 2',
                'input.emptyList ? 1 : 2',
                'input.emptyMap ? 1 : 2',
                'input.s ? 1 : 2',
                'input.nul ? 1 : 2',
                'input.person ? 1 : 2',
                "input.nul ?: 'd'",
                "input.zero ?: 'd'",
                "input.f ?: 'd'",
                "input.s ?: 'd'",
                '!input.list',
                '!input.nul',
                '!input.zero',
                'input.t && input.s',
                'input.f || input.n',
                'input.t && !input.f',
                'input.nul && input.nul.size() > 0',
                'input.s || input.nul.size() > 0',

                // ---- in, match
                '3 in input.list',
                "'a' in input.names",
                "'n' in input",
                "'nul' in input",
                "'zero' in input",
                '9 !in input.list',
                '10 in [input.lng]',
                'input.n in [3L, 4L]',
                '2 in input.arr',
                "'x' in input.sarr",
                "'q' in input.set",
                "'ell' in input.s",
                '3 in input.nul',
                'input.nul in input.list',
                'input.people.tags.empty',
                'input.list.last',
                'input.names.first.toUpperCase()',
                'input.s + input.list',
                'input.s * 2',
                'input.list + input.names',
                'input.d % 2',
                'input.dbl % 1',
                'input.s ==~ /H.*o/',
                'input.s ==~ /ell/',
                'input.s =~ /ell/',
                'input.s =~ /zzz/',

                // ---- equality and literals
                'input.list == [3, 1, 2]',
                'input.list == [1, 2, 3]',
                'input.nested.a == [b: [c: 42]]',
                'input.level === input.level',
                'input.list !== input.names',
                '[1, 2] == [1, 2]',
                '[a: 1].a',
                "[low: 1, high: 2]['high']",
                '[input.n, input.d]',
                '[k: input.n]',

                // ---- Math
                'Math.max(input.n, input.d)',
                'Math.min(input.lng, input.n)',
                'Math.round(input.d)',
                'Math.floor(input.dbl)',
                'Math.abs(-input.n)',
                'Math.sqrt(16)',
                'Math.PI',
                'Math.E > 2'
        ]
    }
}
