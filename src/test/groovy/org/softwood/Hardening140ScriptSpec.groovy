package org.softwood

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.transform.CompileDynamic
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.DefaultTaskEventDispatcher
import org.softwood.dag.task.ScriptTask
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

/**
 * GCU 1.4.0, WP3 steps 2 to 4: the script profile on ScriptTask (decision D2, brief gcu/brief-core-hardening-2026-11-18).
 *
 * A ScriptTask script is restricted by default. It may name only the classes of the profile, may not
 * use the ways out through an object's class, meta class or the script itself, and at run time may
 * not call into files, URLs, processes, threads or reflection, however it came by them. It is
 * refused when the graph is validated where that can be known from the text, and when it runs
 * otherwise. `trusted true` gives the old unrestricted behaviour. `allow 'files'` and `allow 'http'`
 * on the graph give a script guarded helpers. JavaScript runs with no Java access; other languages
 * need `trusted true`.
 *
 * Before 1.4.0 the "sandbox" stopped only a bare execute(): the escapes below all ran.
 */
@CompileDynamic
@Timeout(60)
class Hardening140ScriptSpec extends Specification {

    @TempDir
    Path dir

    static class Person {
        String name = 'Ann'
        int age = 41

        String greet(String other) { "hello ${other} from ${name}".toString() }
    }

    static final Map<String, Object> VARS = [
            items : [[name: 'a', price: 10, qty: 2], [name: 'b', price: 5.5G, qty: 3]],
            text  : 'the quick brown fox jumps over the lazy dog the end',
            n     : 7,
            person: new Person()
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

    /** One ScriptTask, 's', with the given variables bound. */
    private static TaskGraph graphOf(String code, Map<String, Object> vars = VARS, Closure tweak = null) {
        TaskGraph graph = TaskGraph.build { }
        graph.ctx.globals.set('score', 0.4G)
        ScriptTask task = new ScriptTask('s', 's', graph.ctx)
        task.eventDispatcher = new DefaultTaskEventDispatcher(graph)
        task.script = code
        task.bindings { c, p -> vars }
        if (tweak) tweak.call(task)
        graph.addTask(task)
        return graph
    }

    private static Object run(TaskGraph graph) {
        graph.start().get()
    }

    // ------------------------------------------------------------------ refused before anything runs

    def "a script outside the profile is refused when the graph is validated: #code"() {
        given:
        def graph = graphOf(code)

        when:
        def failure = failureOf { graph.validate() }

        then:
        failure instanceof IllegalStateException
        failure.message.contains("'s'")

        where:
        code << [
                // the six escapes of the 2026-11-18 security review
                '"hostname".execute().text',
                'System.getenv("PATH")',
                'new File(".").exists()',
                'new ProcessBuilder("hostname").start().text',
                'Class.forName("java.lang.Runtime")',
                'execute("x")',
                // more text, the script itself, its class
                'evaluate("1 + 1")',
                'this.evaluate("1 + 1")',
                'invokeMethod("evaluate", "1")',
                'this.class.classLoader',
                'this.getBinding()',
                'binding.variables',
                'super.toString()',
                'def run() { 1 }\n1',
                '"x".getClass()',
                '[].metaClass',
                '1.properties',
                'n."${"get" + "Class"}"()',
                'n."${"cla" + "ss"}"',
                '[1, 2].&size',
                'person.@name',
                '"hostname".with { execute() }',
                'person.tap { name = "x" }',
                'use(java.time.Duration) { 1 }',
                'addShutdownHook { }',
                // classes outside the profile
                'new GroovyShell().evaluate("1")',
                'Eval.me("1")',
                'Thread.start { }',
                'new Thread({ } as Runnable)',
                'new URL("http://example.com").text',
                '"http://example.com".toURL().text',
                'new Scanner("x")',
                'new Formatter("f.txt")',
                'new java.util.Timer()',
                'java.util.concurrent.Executors.newCachedThreadPool()',
                'java.lang.reflect.Method m = null\nm',
                'def f = null as File\nf',
                'try { 1 } catch (Throwable t) { 2 }',
                'synchronized (this) { }\n1',
                // a class used for more than its static members
                'System.exit(0)',
                'System.getProperty("user.home")',
                'Integer.getInteger("x")',
                'String.classLoader',
                'String.getClassLoader()',
                'String.name',
                // imports, annotations, definitions
                'import java.io.File\n1',
                'import java.time.LocalDate as LD\nLD.now()',
                'import static java.lang.Math.max\nmax(1, 2)',
                '@Grab("org.example:nothing:1.0") import java.time.LocalDate\n1',
                'class Foo { }\n1',
                '@groovy.transform.CompileStatic def f() { 1 }\nf()'
        ]
    }

    def "an @ASTTest annotation is refused and its code never runs"() {
        given:
        System.clearProperty('gcu.asttest')
        def graph = graphOf('@groovy.transform.ASTTest(value = { System.setProperty("gcu.asttest", "ran") }) def x = 1\nx')

        when:
        def failure = failureOf { graph.validate() }

        then:
        failure instanceof IllegalStateException
        System.getProperty('gcu.asttest') == null

        cleanup:
        System.clearProperty('gcu.asttest')
    }

    def "a script that does not compile fails validation and names the task"() {
        when:
        def failure = failureOf { graphOf('def x = [1, 2\nx').validate() }

        then:
        failure instanceof IllegalStateException
        failure.message.contains("'s'")
    }

    def "a ScriptTask with no script fails validation"() {
        when:
        def failure = failureOf { graphOf(null).validate() }

        then:
        failure instanceof IllegalStateException
        failure.message.contains('script')
    }

    // ------------------------------------------------------------------ refused while running

    def "a script cannot use what it is handed to reach outside: #code"() {
        given:
        Path target = dir.resolve('target.txt')
        Files.writeString(target, 'secret')
        def graph = graphOf(code, [f: target.toFile(), u: new URL('http://example.com/x'), p: new Person()])

        when:
        graph.validate()

        then: 'nothing in the text gives it away'
        noExceptionThrown()

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException)
        Files.readString(target) == 'secret'
        Files.exists(target)

        where:
        code << [
                'f.text',
                'f.name',
                'f << "more"',
                'f.delete()',
                'f.write("changed")',
                '[f]*.name',
                '[f].name',
                '[f].collect { it.length() }',
                'def g = f\ng.text',
                'for (line in f) { return line }\nnull',
                '[*f]',
                'f as List',
                'f as String[]',
                'def (a, b) = f\na',
                'def a\ndef b\n(a, b) = f\na',
                'f.toString()',
                'u.host',
                "p['name']",
                'def c = String\nc.getName()',
                'def c = { 1 }\nc.delegate = "x"\n1'
        ]
    }

    def "a refusal is a permanent failure: it is not retried and the exception says so"() {
        given:
        def calls = new java.util.concurrent.atomic.AtomicInteger()
        def graph = graphOf('calls.incrementAndGet()\nf.text', [f: dir.toFile(), calls: calls]) { ScriptTask t ->
            t.retry { maxAttempts 3; initialDelay Duration.ofMillis(10) }
        }

        when:
        def failure = failureOf { run(graph) }
        def signal = Class.forName('org.softwood.dag.task.TaskFailureSignal').of(failure)

        then:
        causedBy(failure, SecurityException)
        calls.get() == 1
        signal.permanent
        signal.taskId == 's'
    }

    // ------------------------------------------------------------------ what a script can say

    def "the JavaScript example in the task types chapter gives its result"() {
        given:
        def graph = graphOf('var doubled = [];\nfor (var i = 0; i < values.length; i++) { doubled.push(values[i] * 2); }\ndoubled.length;',
                [values: [1, 2, 3] as int[]]) { ScriptTask t -> t.language('javascript') }

        expect:
        (run(graph) as Number).intValue() == 3
    }

    def "a script inside the profile gives what plain Groovy gives: #code"() {
        given:
        def expected = new GroovyShell(new Binding(new LinkedHashMap(VARS))).evaluate(code)

        when:
        def result = run(graphOf(code))

        then:
        result == expected

        where:
        code << [
                'items.sum { it.price * it.qty }',
                'items.findAll { it.price > 6 }.collect { it.name }',
                'def total = 0\nfor (i in items) { total += i.qty }\ntotal',
                'def counts = [:]\ntext.split(" ").each { w -> counts[w] = (counts[w] ?: 0) + 1 }\ncounts',
                'def counts = [:].withDefault { 0 }\ntext.tokenize().each { counts[it]++ }\ncounts.the',
                'def m = [:]\nm.a = 1\nm.b = m.a + 1\nm["c"] = 3\nm.b += 10\nm',
                'def list = []\nlist << 1\nlist << 2\nlist += [3]\nlist',
                'def (a, b) = [1, 2]\na + b',
                'int i = 0\nwhile (i < 5) { i++ }\ni',
                'def sb = new StringBuilder()\n(1..3).each { sb.append(it) }\nsb.toString()',
                'text.toUpperCase().split(" ").take(3).join("-")',
                'items.collectEntries { [(it.name): it.price] }',
                'items.groupBy { it.qty > 2 }.collectEntries { k, v -> [(k): v.size()] }',
                'items.sort(false) { -it.price }*.name',
                'items*.name',
                'items.name',
                'def fact\nfact = { x -> x <= 1 ? 1 : x * fact(x - 1) }\nfact(5)',
                'def add = { a, b -> a + b }\ndef inc = add.curry(1)\ninc(n)',
                'switch (n) { case 1..5: return "low"; case 6..10: return "mid"; default: return "high" }',
                'try { Integer.parseInt("x") } catch (NumberFormatException e) { -1 }',
                'try { throw new IllegalStateException("boom") } catch (Exception e) { e.message }',
                'String.format("%05d", n)',
                'Math.max(n, 3) + Math.abs(-2)',
                'new BigDecimal("1.10").add(2.5G).setScale(1, java.math.RoundingMode.HALF_UP)',
                'java.time.LocalDate.parse("2026-11-20").plusDays(n).toString()',
                'java.time.Duration.ofMinutes(90).toHours()',
                '(text =~ /o/).count',
                'text.replaceAll(/\\s+/, "_").size()',
                'def m = (text =~ /(\\w+) (\\w+)/)\nm.find() ? m.group(2) : null',
                'def m = (text =~ /(\\w+) (\\w+)/)\nm[0][1]',
                'text.findAll(/\\b\\w{5}\\b/)',
                '[3, 1, 2].stream().map { it * 2 }.sorted().collect(java.util.stream.Collectors.toList())',
                'Optional.ofNullable(null).orElse("none")',
                'UUID.nameUUIDFromBytes("x".bytes).toString()',
                'Base64.encoder.encodeToString("hello".bytes)',
                'groovy.json.JsonOutput.toJson([a: 1, b: [2, 3]])',
                'new groovy.json.JsonSlurper().parseText(\'{"a": {"b": [1, 2]}}\').a.b[1]',
                'person.name + ":" + person.age',
                'person.greet("x")',
                'def helper(x) { x * 2 }\nhelper(n) + helper(1)',
                '@groovy.transform.Field def base = 10\ndef plus(x) { base + x }\nplus(n)',
                'n > 5 ? "big ${n}" : "small"',
                'items.inject(0) { acc, it -> acc + it.qty }',
                'items.any { it.qty == 3 } && items.every { it.price > 1 }',
                '[1, 2, 3].collect { it * n }.findAll { it % 2 == 0 }.max()',
                'def r = []\nouter: for (a in 1..3) { for (b in 1..3) { if (b == 2) continue outer; r << a * b } }\nr',
                'text.chars.findAll { it == ("o" as char) }.size()',
                'items.max { it.price }.name',
                'new Date(0).time',
                'Collections.unmodifiableList([1, 2]).size() + Arrays.asList(1, 2, 3).size()',
                '(items.find { it.name == "zzz" }?.price) == null',
                'def x = null\nx?.foo?.bar ?: "none"',
                'def copy = items.collect { it + [total: it.price * it.qty] }\ncopy*.total',
                'import java.time.LocalDate\nLocalDate.of(2026, 1, 31).monthValue',
                'java.time.DayOfWeek.MONDAY.plus(n).toString()',
                'System.currentTimeMillis() > 0 && System.nanoTime() != 0',
                'text.split(" ") as Set',
                'def words = text.split(" ") as List<String>\nwords.unique(false).size()',
                'def s = 0\n10.times { s += it }\ns',
                'items.withIndex().collect { item, idx -> "${idx}:${item.name}" }',
                'def e = new Expando(a: 1)\ne.b = 2\ne.a + e.b',
                'try { missingThing } catch (Exception e) { e.message.contains("missingThing") }',
                'try { [].iterator().next() } catch (NoSuchElementException e) { "none" }',
                'java.nio.charset.StandardCharsets.UTF_8.name()',
                'new String("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8)',
                'java.security.MessageDigest.getInstance("SHA-256").digest("x".bytes).size()',
                'TimeZone.getTimeZone("UTC").getID()',
                'Calendar.getInstance().get(Calendar.YEAR) > 2000',
                'java.text.NumberFormat.getIntegerInstance(Locale.UK).format(1234567)',
                'def all = []\nfor (i in items) { all << i.name }\nfor (int k = 0; k < 2; k++) { all << k }\nall',
                'def join = { String... parts -> parts.join("+") }\njoin(*["a", "b"])',
                '[*items*.name, "z"]',
                'def (first, second) = items*.name\nsecond + first',
                '(n as BigDecimal) / 2',
                '[a: 1, *: [b: 2]]'
        ]
    }

    def "the base class helpers work in a restricted script"() {
        expect:
        run(graphOf('def parsed = parseJson(\'{"k": [1, 2, 3]}\')\nlog "parsed ${parsed.k.size()}"\n[json: toJson([sum: parsed.k.sum()]), late: now() > 0]')) ==
                [json: '{"sum":6}', late: true]
    }

    def "the default bindings are there: prev, input, ctx and context"() {
        expect:
        run(graphOf('[ctx != null, context != null, ctx.taskId, ctx.globals.score, ctx.get("score"), context.globals.containsKey("score")]')) ==
                [true, true, 's', 0.4G, 0.4G, true]
    }

    // ------------------------------------------------------------------ the run-time rules

    def "ctx is a read-only view: a restricted script finds no credentials, pool or graph on it"() {
        expect: 'a name the view does not have is looked up in the globals, and is not there'
        run(graphOf('[ctx.credentials, ctx.pool, ctx.graph, context.credentials]')) == [null, null, null, null]
    }

    def "ctx is a read-only view: a restricted script cannot change globals: #code"() {
        given:
        def graph = graphOf(code)

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        graph.ctx.globals.get('planted') == null

        where:
        code << [
                'ctx.globals.put("planted", 1)',
                'ctx.globals.planted = 1',
                'ctx.globals.clear()',
                'ctx.globals.remove("score")'
        ]
    }

    def "each run has its own script instance and binding: nothing leaks from one run to the next"() {
        given:
        def graph = TaskGraph.build { }
        ScriptTask task = new ScriptTask('s', 's', graph.ctx)
        task.script = 'def seen\ntry { seen = leak } catch (Exception e) { seen = "clean" }\nleak = input\nseen'

        when:
        def first = task.execute(graph.ctx.promiseFactory.createPromise('one')).get()
        task.state = org.softwood.dag.task.TaskState.SCHEDULED
        task.completionPromise = null
        def second = task.execute(graph.ctx.promiseFactory.createPromise('two')).get()

        then:
        first == 'clean'
        second == 'clean'
    }

    @Timeout(20)
    def "scriptTimeout stops a script that will not end: #code"() {
        given:
        def graph = graphOf(code, VARS) { ScriptTask t -> t.scriptTimeout(Duration.ofMillis(300)) }
        long started = System.currentTimeMillis()

        when:
        def failure = failureOf { run(graph) }
        long took = System.currentTimeMillis() - started

        then:
        failure != null
        took < 10_000

        where:
        code << [
                'while (true) { }',
                'while (true) { try { while (true) { } } catch (Exception e) { } }',
                'def spin\nspin = { -> spin() }\ntry { spin() } catch (Exception e) { }\nwhile (true) { [1, 2].each { } }',
                'sleep(30000)\n1'
        ]
    }

    @Timeout(20)
    def "a script stopped by its scriptTimeout really stops, even one that catches every Exception: #code"() {
        given:
        def ticks = new java.util.concurrent.atomic.AtomicLong()
        def graph = graphOf(code, [ticks: ticks]) { ScriptTask t -> t.scriptTimeout(Duration.ofMillis(300)) }

        when:
        def failure = failureOf { run(graph) }
        Thread.sleep(700)
        long then1 = ticks.get()
        Thread.sleep(400)
        long then2 = ticks.get()

        then:
        failure != null
        then1 > 0
        then2 == then1

        where:
        code << [
                'while (true) { ticks.incrementAndGet() }',
                'while (true) { try { while (true) { ticks.incrementAndGet() } } catch (Exception e) { } }',
                'def loop\nloop = { -> while (true) { try { [1, 2, 3].each { ticks.incrementAndGet() } } catch (Exception e) { } } }\nloop()'
        ]
    }

    def "a script that finishes inside its scriptTimeout gives its result"() {
        expect:
        run(graphOf('items.size()', VARS) { ScriptTask t -> t.scriptTimeout(Duration.ofSeconds(10)) }) == 2
    }

    // ------------------------------------------------------------------ trusted true

    def "trusted true runs a script as unrestricted Groovy with the real context"() {
        given:
        def graph = graphOf('ctx.globals.set("planted", System.getProperty("user.dir") != null)\nnew File(".").exists()', VARS) {
            ScriptTask t -> t.trusted(true)
        }

        expect:
        run(graph) == true
        graph.ctx.globals.get('planted') == true
    }

    def "sandboxed false is the old word for trusted true, and no longer reads the environment"() {
        given:
        String before = System.getProperty('environment')
        System.setProperty('environment', 'production')
        def graph = graphOf('new File(".").exists()', VARS) { ScriptTask t -> t.sandboxed(false) }

        expect:
        graph.tasks['s'].trusted
        !graph.tasks['s'].sandboxed
        run(graph) == true

        cleanup:
        if (before == null) System.clearProperty('environment') else System.setProperty('environment', before)
    }

    def "a trusted script that does not compile still fails validation"() {
        when:
        def failure = failureOf { graphOf('def x = [1, 2\nx', VARS) { ScriptTask t -> t.trusted(true) }.validate() }

        then:
        failure instanceof IllegalStateException
    }

    // ------------------------------------------------------------------ level 2: allow 'files'

    def "allow 'files' gives a script guarded reading and writing inside the working directory"() {
        given:
        Files.writeString(dir.resolve('in.txt'), 'alpha')
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'files'
            workingDirectory where
            scriptTask('s') {
                script 'files.write("out.txt", files.read("in.txt").toUpperCase())\nfiles.append("out.txt", "!")\n[files.list("."), files.exists("nope.txt"), files.size("in.txt")]'
            }
        }

        expect:
        run(graph) == [['in.txt', 'out.txt'], false, 5L]
        Files.readString(dir.resolve('out.txt')) == 'ALPHA!'
    }

    def "the files capability refuses a path outside the graph's directories: #path"() {
        given:
        Path inside = Files.createDirectory(dir.resolve('inside'))
        Files.writeString(dir.resolve('outside.txt'), 'secret')
        String where = inside.toString()
        String target = path.replace('OUTSIDE', dir.resolve('outside.txt').toString().replace('\\', '/'))
        def graph = TaskGraph.build {
            allow 'files'
            workingDirectory where
            scriptTask('s') { script "files.read('${target}')".toString() }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException)

        where:
        path << ['OUTSIDE', '../outside.txt']
    }

    def "without allow 'files' a script has no files helper and still cannot name File"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            workingDirectory where
            scriptTask('s') { script 'files.exists("in.txt")' }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        messages(failure).contains('files')
    }

    def "allow accepts only the capabilities this version has"() {
        when:
        TaskGraph.build { allow capability }

        then:
        thrown(IllegalArgumentException)

        where:
        capability << ['process', 'sql', 'everything']
    }

    // ------------------------------------------------------------------ level 2: allow 'http'

    def "allow 'http' gives a script requests that the HttpTask guard has checked"() {
        given:
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort())
        server.start()
        server.stubFor(get(urlEqualTo('/data')).willReturn(aResponse().withStatus(200).withBody('from-a')))
        int port = server.port()
        String local = "localhost:${port}".toString()
        String code = "def r = http.get('http://localhost:${port}/data')\n[r.status, r.body]".toString()

        when: 'the graph names the local service'
        def named = run(TaskGraph.build {
            allow 'http'
            allowLocal local
            scriptTask('s') { script code }
        })

        and: 'it does not'
        def unnamed = failureOf {
            run(TaskGraph.build {
                allow 'http'
                scriptTask('s') { script code }
            })
        }

        and: 'a cloud metadata address'
        def metadata = failureOf {
            run(TaskGraph.build {
                allow 'http'
                scriptTask('s') { script 'http.get("http://169.254.169.254/latest/meta-data/")' }
            })
        }

        then:
        named == [200, 'from-a']
        causedBy(unnamed, SecurityException)
        causedBy(metadata, SecurityException)
        server.findAll(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo('/data'))).size() == 1

        cleanup:
        server?.stop()
    }

    // ------------------------------------------------------------------ other languages

    def "restricted JavaScript computes with what it is given"() {
        given:
        def graph = graphOf('var t = a * b; t + 1', [a: 2, b: 3]) { ScriptTask t -> t.language('javascript') }

        expect:
        (run(graph) as Number).intValue() == 7
    }

    def "restricted JavaScript has no way into Java: #code"() {
        given:
        def graph = graphOf(code, [p: new Person()]) { ScriptTask t -> t.language('javascript') }

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null

        where:
        code << [
                'Java.type("java.lang.Runtime").getRuntime()',
                'java.lang.System.getProperty("user.dir")',
                'Packages.java.lang.System.getProperty("user.dir")',
                'p.getClass().forName("java.lang.Runtime")',
                'new java.io.File(".").exists()'
        ]
    }

    def "a language other than Groovy and JavaScript needs trusted true: #language"() {
        when:
        def failure = failureOf { graphOf('1 + 1', VARS) { ScriptTask t -> t.language(language) }.validate() }

        then:
        failure instanceof IllegalStateException
        failure.message.contains('trusted')

        where:
        language << ['python', 'ruby', 'jython']
    }
}
