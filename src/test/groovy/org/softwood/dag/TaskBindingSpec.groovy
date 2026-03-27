package org.softwood.dag

import spock.lang.Specification
import spock.lang.Title
import spock.lang.Unroll

/**
 * TaskBindingSpec — P4 regression spec
 *
 * Covers the filtered globals methods added to IBinding/TaskBinding:
 *   getFiltered(keyPattern)   — regex filter (delegates to filter())
 *   getPage(offset, size)     — paginated slice ordered by key (new in GCU 1.1.1)
 *   getKeys(pattern)          — regex-filtered key set
 *   prefix(prefix)            — prefix filter
 */
@Title("TaskBinding: filtered globals and pagination methods")
class TaskBindingSpec extends Specification {

    TaskBinding binding

    def setup() {
        binding = new TaskBinding()
        // Populate with a known set — dot-namespaced like real flow globals
        ['o365.mail.userId'  : 'user@corp.com',
         'o365.mail.maxItems': 10,
         'o365.teams.teamId' : 'team-1',
         'o365.teams.chanId' : 'chan-1',
         'llm.model'         : 'llama3',
         'llm.temperature'   : 0.7,
         '__workingMemory'   : 'wm-ref',
        ].each { k, v -> binding.set(k, v) }
    }

    // ── getFiltered (regex) ───────────────────────────────────────────────────

    def "getFiltered returns entries whose key fully matches regex"() {
        when:
        def result = binding.getFiltered('o365\\.mail\\..*')

        then:
        result.size() == 2
        result.containsKey('o365.mail.userId')
        result.containsKey('o365.mail.maxItems')
        !result.containsKey('o365.teams.teamId')
    }

    def "getFiltered returns empty map when nothing matches"() {
        when:
        def result = binding.getFiltered('no\\.match\\..*')

        then:
        result.isEmpty()
    }

    def "getFiltered is available via IBinding interface"() {
        given:
        IBinding ib = binding

        when:
        def result = ib.getFiltered('llm\\..*')

        then:
        result.size() == 2
        result.containsKey('llm.model')
        result.containsKey('llm.temperature')
    }

    // ── prefix ────────────────────────────────────────────────────────────────

    def "prefix returns all entries starting with given prefix"() {
        when:
        def result = binding.prefix('o365.')

        then:
        result.size() == 4
        result.keySet().every { it.startsWith('o365.') }
    }

    def "prefix with exact key returns single entry"() {
        when:
        def result = binding.prefix('llm.model')

        then:
        result.size() == 1
        result['llm.model'] == 'llama3'
    }

    def "prefix returns empty map when nothing matches"() {
        expect:
        binding.prefix('nomatch.').isEmpty()
    }

    // ── getKeys(pattern) ─────────────────────────────────────────────────────

    def "getKeys(pattern) returns keys matching regex"() {
        when:
        def keys = binding.getKeys('o365\\.teams\\..*')

        then:
        keys.size() == 2
        keys.contains('o365.teams.teamId')
        keys.contains('o365.teams.chanId')
    }

    def "getKeys() with no args returns all keys"() {
        when:
        def keys = binding.getKeys()

        then:
        keys.size() == 7
    }

    // ── getPage ───────────────────────────────────────────────────────────────

    def "getPage returns first page sorted by key"() {
        when:
        // Keys sorted: __workingMemory, llm.model, llm.temperature, o365.mail.maxItems,
        //              o365.mail.userId, o365.teams.chanId, o365.teams.teamId
        def page = binding.getPage(0, 3)

        then:
        page.size() == 3
        page.keySet().toList() == ['__workingMemory', 'llm.model', 'llm.temperature']
    }

    def "getPage with offset returns correct slice"() {
        when:
        def page = binding.getPage(3, 3)

        then:
        page.size() == 3
        page.keySet().toList() == ['o365.mail.maxItems', 'o365.mail.userId', 'o365.teams.chanId']
    }

    def "getPage returns partial page when fewer items remain"() {
        when:
        def page = binding.getPage(6, 3)

        then:
        page.size() == 1
        page.keySet().first() == 'o365.teams.teamId'
    }

    def "getPage returns empty map when offset beyond size"() {
        when:
        def page = binding.getPage(99, 3)

        then:
        page.isEmpty()
    }

    def "getPage with size=0 returns all from offset"() {
        when:
        def page = binding.getPage(5, 0)

        then:
        page.size() == 2
        page.keySet().toList() == ['o365.teams.chanId', 'o365.teams.teamId']
    }

    def "getPage is available via IBinding default method"() {
        given:
        IBinding ib = binding

        when:
        def page = ib.getPage(0, 2)

        then:
        page.size() == 2
    }

    // ── thread safety smoke ───────────────────────────────────────────────────

    def "concurrent writes and getFiltered do not throw"() {
        given:
        def threads = (1..10).collect { i ->
            Thread.start { binding.set("concurrent.key.${i}", i) }
        }

        when:
        threads*.join()
        def result = binding.getFiltered('concurrent\\.key\\..*')

        then:
        result.size() == 10
        noExceptionThrown()
    }
}
