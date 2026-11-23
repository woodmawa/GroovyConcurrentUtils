package org.softwood

import groovy.transform.CompileDynamic
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.TaskFailureSignal
import org.softwood.dag.task.sql.H2SqlProvider
import spock.lang.Specification
import spock.lang.Timeout

import java.time.Duration

/**
 * GCU 1.4.1 (decision D2, remainder; design in project doc gcu/design-1.4.1.md): allow 'sql'.
 *
 * The flow author declares a datasource by name in the graph (`datasource 'orders', provider`).
 * `allow 'sql'` gives a restricted script `sql`, which takes that name, a statement with ?
 * placeholders and its parameters. A script can never supply a URL, a driver or a provider, and
 * cannot reach the provider or a connection. A datasource is read-only unless declared
 * `writable: true`; one statement per call; results are capped (10,000 rows by default).
 *
 * Written against the API as it does not yet exist (1.4.0): everything is dynamic.
 */
@CompileDynamic
@Timeout(60)
class Hardening141SqlSpec extends Specification {

    H2SqlProvider provider

    def setup() {
        provider = new H2SqlProvider(databaseName: "gcu141_${UUID.randomUUID().toString().replace('-', '')}".toString())
        provider.executeUpdate('create table orders (id int primary key, name varchar(40), qty int)')
        provider.executeUpdate("insert into orders values (1, 'apple', 3), (2, 'pear', 5)")
    }

    def cleanup() {
        provider?.close()
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

    private static Object run(TaskGraph graph) {
        graph.start().get()
    }

    /** A graph declaring `orders` on the provider, with allow 'sql', and one script 's'. */
    private TaskGraph graphOf(String code, Map options = [:]) {
        def p = provider
        return TaskGraph.build {
            allow 'sql'
            if (options) {
                datasource options, 'orders', p
            } else {
                datasource 'orders', p
            }
            scriptTask('s') { script code }
        }
    }

    private int count() {
        provider.queryForObject('select count(*) from orders') as int
    }

    // ------------------------------------------------------------------ reading

    def "allow 'sql' gives a script parameterised queries on a datasource the graph named"() {
        given:
        def graph = graphOf('''def rows = sql.query('orders', 'select name, qty from orders where qty > ? order by id', [2])
def one = sql.queryForMap('orders', 'select name from orders where id = ?', [2])
def n = sql.queryForObject('orders', 'select count(*) from orders')
[rows.collect { it.name }, one.name, n as int]''')   // JdbcSqlProvider lower-cases column labels

        expect:
        run(graph) == [['apple', 'pear'], 'pear', 2]
    }

    def "a WITH query is a read"() {
        expect:
        run(graphOf('''sql.queryForObject('orders', 'with t as (select qty from orders) select sum(qty) from t') as int''')) == 8
    }

    def "a datasource the graph did not name is refused, and the refusal is permanent"() {
        given:
        def graph = graphOf('''sql.query('payroll', 'select 1')''')

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException)
        messages(failure).contains('payroll')
        TaskFailureSignal.isPermanent(failure)
    }

    def "without allow 'sql' a script has no sql helper"() {
        given:
        def p = provider
        def graph = TaskGraph.build {
            datasource 'orders', p
            scriptTask('s') { script '''sql.query('orders', 'select 1')''' }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        messages(failure).contains('sql')
    }

    // ------------------------------------------------------------------ read-only by default

    def "a datasource is read-only unless the graph declares it writable: #code"() {
        given:
        def graph = graphOf(code)

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException)
        count() == 2

        where:
        code << [
                '''sql.update('orders', "insert into orders values (3, 'fig', 1)")''',
                '''sql.update('orders', 'delete from orders')''',
                '''sql.query('orders', 'delete from orders')''',
                '''sql.queryForObject('orders', 'update orders set qty = 0')''',
                // a write inside a query: H2's data change delta tables delete and return the rows
                '''sql.query('orders', 'select * from old table (delete from orders)')''',
                '''sql.queryForObject('orders', 'with gone as (select * from old table (delete from orders)) select count(*) from gone')''',
        ]
    }

    def "a writable datasource takes inserts, updates and deletes through update"() {
        given:
        def graph = graphOf('''[sql.update('orders', 'insert into orders values (?, ?, ?)', [3, 'fig', 1]),
 sql.update('orders', 'update orders set qty = qty + 1 where id = ?', [1]),
 sql.update('orders', 'delete from orders where id = ?', [2])]''', [writable: true])

        expect:
        run(graph) == [1, 1, 1]
        count() == 2
        provider.queryForObject('select qty from orders where id = 1') as int == 4
    }

    def "even a writable datasource refuses DDL and other statements through update: #code"() {
        given:
        def graph = graphOf(code, [writable: true])

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException)
        count() == 2

        where:
        code << [
                '''sql.update('orders', 'drop table orders')''',
                '''sql.update('orders', 'truncate table orders')''',
                '''sql.update('orders', 'alter table orders add column x int')''',
                '''sql.update('orders', 'select * from orders')''',
        ]
    }

    // ------------------------------------------------------------------ one statement per call

    def "one statement per call: a second statement is refused, wherever it hides: #code"() {
        given:
        def graph = graphOf(code, [writable: true])

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException)
        count() == 2

        where:
        code << [
                '''sql.query('orders', 'select 1; delete from orders')''',
                '''sql.update('orders', 'delete from orders where id = 9; delete from orders')''',
                '''sql.query('orders', 'select 1 /* x */; delete from orders')''',
                '''sql.query('orders', 'select 1 -- x\\n; delete from orders')''',
        ]
    }

    def "a semicolon inside quoted text, a comment, or one trailing semicolon is not a second statement"() {
        given:
        def graph = graphOf('''[sql.queryForObject('orders', "select count(*) from orders where name <> 'a;b'"),
 sql.queryForObject('orders', 'select count(*) from orders -- trailing; note'),
 sql.queryForObject('orders', 'select count(*) from orders;'),
 sql.queryForObject('orders', '/* lead; */ select count(*) from orders')].collect { it as int }''')

        expect:
        run(graph) == [2, 2, 2, 2]
    }

    def "a comment before the keyword does not hide a write on a read-only datasource"() {
        given:
        def graph = graphOf('''sql.query('orders', '/* select */ delete from orders')''')

        expect:
        causedBy(failureOf { run(graph) }, SecurityException)
        count() == 2
    }

    // ------------------------------------------------------------------ limits

    def "a result over the row cap fails the task rather than being cut short"() {
        given:
        def graph = graphOf('''sql.query('orders', 'select * from orders')''', [maxRows: 1])

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        messages(failure).contains('row')
    }

    def "the default row cap is 10,000"() {
        given:
        def graph = graphOf('''sql.query('orders', 'select x from system_range(1, 10001)')''')

        expect:
        messages(failureOf { run(graph) }).contains('10000')
        run(graphOf('''sql.query('orders', 'select x from system_range(1, 10000)').size()''')) == 10000
    }

    // ------------------------------------------------------------------ what a script cannot reach

    def "a script cannot reach the provider, a connection or the datasource list: #code"() {
        given:
        def graph = graphOf(code)

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        count() == 2

        where:
        code << [
                '''sql.execute { c -> c }''',
                '''sql.withTransaction { c -> c }''',
                '''sql.provider''',
                '''sql.datasources''',
                '''sql.ctx''',
                '''sql.properties''',
                '''sql.close()''',
        ]
    }

    // ------------------------------------------------------------------ audit

    def "each call leaves an audit record: datasource, statement hash, rows"() {
        given:
        String statement = 'select * from orders'
        def graph = graphOf("sql.query('orders', '${statement}').size()".toString())
        List<Map> records = [].asSynchronized()
        graph.ctx.auditListener = { Map r -> records << r }

        when:
        run(graph)

        then:
        records.size() == 1
        records[0].kind == 'sql'
        records[0].datasource == 'orders'
        records[0].rows == 2
        records[0].statementSha256 == java.security.MessageDigest.getInstance('SHA-256').digest(statement.getBytes('UTF-8')).encodeHex().toString()
    }

    // ------------------------------------------------------------------ DSL

    def "a datasource needs a name and an SqlProvider"() {
        when:
        TaskGraph.build { datasource name, value }

        then:
        thrown(IllegalArgumentException)

        where:
        name     | value
        ''       | new H2SqlProvider()
        'orders' | 'jdbc:h2:mem:x'
    }
}
