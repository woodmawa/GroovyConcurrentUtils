package org.softwood.actor

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.DisplayName

import java.time.Duration
import java.util.concurrent.TimeUnit

import static org.awaitility.Awaitility.await
import static org.junit.jupiter.api.Assertions.*

/**
 * JUnit 5 tests for new GroovyActor features:
 * - CounterMap
 * - ctx.createCounterMap()
 * - ctx.spawnForEach()
 * - ActorPatterns.aggregatorPattern()
 * 
 * Uses Awaitility for async testing instead of Spock.
 */
class ActorNewFeaturesTest {
    
    ActorSystem system
    
    @BeforeEach
    void setup() {
        system = new ActorSystem('test-system')
    }
    
    @AfterEach
    void cleanup() {
        system?.shutdown()
    }
    
    // ═══════════════════════════════════════════════════════════
    // CounterMap Tests
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @DisplayName("CounterMap should increment counts correctly")
    void testCounterMapIncrementsCounts() {
        def counter = new CounterMap()
        
        counter.increment('groovy')
        counter.increment('java')
        counter.increment('groovy')
        
        assertEquals(2, counter.get('groovy'))
        assertEquals(1, counter.get('java'))
        assertEquals(0, counter.get('missing'))
        assertEquals(3, counter.total())
    }
    
    @Test
    @DisplayName("CounterMap should increment by custom amounts")
    void testCounterMapIncrementsWithCustomAmounts() {
        def counter = new CounterMap()
        
        counter.increment('groovy', 5)
        counter.increment('java', 3)
        
        assertEquals(5, counter.get('groovy'))
        assertEquals(3, counter.get('java'))
        assertEquals(8, counter.total())
    }
    
    @Test
    @DisplayName("CounterMap should decrement correctly")
    void testCounterMapDecrements() {
        def counter = new CounterMap()
        
        counter.increment('groovy', 10)
        counter.decrement('groovy', 3)
        
        assertEquals(7, counter.get('groovy'))
    }
    
    @Test
    @DisplayName("CounterMap should handle toMap conversion")
    void testCounterMapToMapConversion() {
        def counter = new CounterMap()
        
        counter.increment('groovy', 2)
        counter.increment('java', 3)
        
        def map = counter.toMap()
        
        assertTrue(map instanceof Map)
        assertEquals(2, map['groovy'])
        assertEquals(3, map['java'])
        assertEquals(2, map.size())
    }
    
    @Test
    @DisplayName("CounterMap should merge with another CounterMap")
    void testCounterMapMerge() {
        def counter1 = new CounterMap()
        counter1.increment('groovy', 5)
        counter1.increment('java', 3)
        
        def counter2 = new CounterMap()
        counter2.increment('groovy', 2)
        counter2.increment('kotlin', 4)
        
        counter1.merge(counter2)
        
        assertEquals(7, counter1.get('groovy'))
        assertEquals(3, counter1.get('java'))
        assertEquals(4, counter1.get('kotlin'))
    }
    
    @Test
    @DisplayName("CounterMap should work in nested closures")
    void testCounterMapInNestedClosures() {
        def counter = new CounterMap()
        
        // Simulate nested closure scenario
        ['a', 'b', 'c'].each { item ->
            [1, 2].each { num ->
                counter.increment(item)
            }
        }
        
        assertEquals(2, counter.get('a'))
        assertEquals(2, counter.get('b'))
        assertEquals(2, counter.get('c'))
        assertEquals(6, counter.total())
    }
    
    // ═══════════════════════════════════════════════════════════
    // ctx.createCounterMap() Tests
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("ctx.createCounterMap should work in actor context")
    void testCreateCounterMapInActorContext() {
        def actor = system.actor {
            name 'counter-actor'
            
            onMessage { msg, ctx ->
                if (msg.type == 'count') {
                    def counts = ctx.createCounterMap()
                    
                    msg.items.each { item ->
                        counts.increment(item)
                    }
                    
                    ctx.reply([done: true, counts: counts.toMap(), total: counts.total()])
                }
            }
        }
        
        def result = actor.askSync([type: 'count', items: ['a', 'b', 'a', 'c', 'b', 'a']], Duration.ofSeconds(2))
        
        assertNotNull(result)
        assertEquals(true, result.done)
        assertEquals(3, result.counts['a'])
        assertEquals(2, result.counts['b'])
        assertEquals(1, result.counts['c'])
        assertEquals(6, result.total)
    }
    
    // ═══════════════════════════════════════════════════════════
    // ctx.spawnForEach() Tests
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("ctx.spawnForEach should create children for each item")
    void testSpawnForEachCreatesChildren() {
        def parent = system.actor {
            name 'parent'
            
            onMessage { msg, ctx ->
                if (msg.type == 'create-children') {
                    def items = msg.items
                    
                    def children = ctx.spawnForEach(items, 'child') { item, index, childMsg, childCtx ->
                        if (childMsg.type == 'process') {
                            childCtx.reply([item: item, index: index])
                        }
                    }
                    
                    ctx.reply([childrenCreated: children.size()])
                }
            }
        }
        
        def result = parent.askSync([type: 'create-children', items: ['a', 'b', 'c', 'd']], Duration.ofSeconds(2))
        
        assertNotNull(result)
        assertEquals(4, result.childrenCreated)
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("ctx.spawnForEach children should process items correctly")
    void testSpawnForEachChildrenProcessItems() {
        def parent = system.actor {
            name 'parent'
            
            onMessage { msg, ctx ->
                if (msg.type == 'spawn-and-process') {
                    def items = msg.items
                    
                    def children = ctx.spawnForEach(items, 'worker') { item, index, childMsg, childCtx ->
                        if (childMsg.type == 'work') {
                            // Process item
                            def result = item.toUpperCase()
                            childCtx.reply([item: item, result: result, index: index])
                        }
                    }
                    
                    // Send work to each child and collect results
                    def childResults = []
                    children.each { child ->
                        def childResult = child.askSync([type: 'work'], Duration.ofSeconds(1))
                        childResults << childResult
                    }
                    
                    ctx.reply([results: childResults])
                }
            }
        }
        
        def result = parent.askSync([type: 'spawn-and-process', items: ['a', 'b', 'c']], Duration.ofSeconds(3))
        
        assertNotNull(result)
        assertEquals(3, result.results.size())
        assertTrue(result.results.any { it.result == 'A' && it.index == 0 })
        assertTrue(result.results.any { it.result == 'B' && it.index == 1 })
        assertTrue(result.results.any { it.result == 'C' && it.index == 2 })
    }
    
    // ═══════════════════════════════════════════════════════════
    // ActorPatterns.aggregatorPattern() Tests
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("aggregatorPattern should collect results from multiple workers")
    void testAggregatorPatternCollectsResults() {
        def completionCalled = false
        def collectedResults = []
        
        def aggregator = system.actor {
            name 'aggregator'
            
            onMessage ActorPatterns.aggregatorPattern(3) { results, ctx ->
                completionCalled = true
                collectedResults = results
                ctx.reply([done: true, results: results])
            }
        }
        
        // Send results from "workers"
        aggregator.tell([type: 'result', data: [count: 10]])
        aggregator.tell([type: 'result', data: [count: 20]])
        
        // Not done yet with 2 results
        Thread.sleep(100)
        assertFalse(completionCalled)
        
        // Send third result - should trigger completion
        def finalResult = aggregator.askSync([type: 'result', data: [count: 30]], Duration.ofSeconds(2))
        
        assertTrue(completionCalled)
        assertEquals(3, collectedResults.size())
        assertTrue(finalResult.done)
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("aggregatorPattern should handle results in correct order")
    void testAggregatorPatternResultsOrder() {
        def results = []
        
        def aggregator = system.actor {
            name 'agg'
            
            onMessage ActorPatterns.aggregatorPattern(5) { collectedResults, ctx ->
                results = collectedResults
            }
        }
        
        // Send 5 results
        (1..5).each { num ->
            aggregator.tell([type: 'result', data: num])
        }
        
        // Wait for completion
        await().atMost(2, TimeUnit.SECONDS).until(() -> results.size() == 5)
        
        assertEquals(5, results.size())
        assertEquals([1, 2, 3, 4, 5], results)
    }
    
    // ═══════════════════════════════════════════════════════════
    // ActorPatterns Helper Tests
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @DisplayName("ActorPatterns.getExtension should extract extensions correctly")
    void testGetExtensionHelper() {
        assertEquals('groovy', ActorPatterns.getExtension('test.groovy'))
        assertEquals('java', ActorPatterns.getExtension('Main.java'))
        assertEquals('gz', ActorPatterns.getExtension('archive.tar.gz'))
        assertEquals('no-extension', ActorPatterns.getExtension('README'))
        assertEquals('no-extension', ActorPatterns.getExtension(''))
        assertEquals('no-extension', ActorPatterns.getExtension(null))
    }
    
    @Test
    @DisplayName("ActorPatterns.uniqueName should generate unique names")
    void testUniqueNameHelper() {
        def name1 = ActorPatterns.uniqueName('worker')
        def name2 = ActorPatterns.uniqueName('worker')
        
        assertTrue(name1.startsWith('worker-'))
        assertTrue(name2.startsWith('worker-'))
        assertNotEquals(name1, name2)
    }
    
    // ═══════════════════════════════════════════════════════════
    // Integration Tests
    // ═══════════════════════════════════════════════════════════
    
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Integration: CounterMap + spawnForEach + aggregator")
    void testIntegrationAllFeatures() {
        // Create coordinator that uses all new features
        def coordinator = system.actor {
            name 'coordinator'
            state counts: [:], pendingWorkers: 0
            
            onMessage { msg, ctx ->
                if (msg.type == 'process-batch') {
                    ctx.deferReply()
                    
                    def items = msg.items
                    def totalCounts = ctx.createCounterMap()
                    
                    // Spawn workers for each item
                    def workers = ctx.spawnForEach(items, 'worker') { item, index, workerMsg, workerCtx ->
                        if (workerMsg.type == 'count') {
                            def localCounts = workerCtx.createCounterMap()
                            item.each { localCounts.increment(it) }
                            workerCtx.reply([counts: localCounts.toMap()])
                        }
                    }
                    
                    // Collect results from workers
                    workers.each { worker ->
                        def result = worker.askSync([type: 'count'], Duration.ofSeconds(1))
                        result.counts.each { key, count ->
                            totalCounts.increment(key as String, count as Integer)
                        }
                    }
                    
                    ctx.reply([done: true, counts: totalCounts.toMap(), total: totalCounts.total()])
                }
            }
        }
        
        def result = coordinator.askSync([
            type: 'process-batch',
            items: [
                ['a', 'b', 'a'],
                ['b', 'c'],
                ['a', 'c', 'c']
            ]
        ], Duration.ofSeconds(5))
        
        assertNotNull(result)
        assertTrue(result.done)
        assertEquals(3, result.counts['a'])
        assertEquals(2, result.counts['b'])
        assertEquals(3, result.counts['c'])
        assertEquals(8, result.total)
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("ctx.deferReply should prevent auto-reply")
    void testDeferReplyPreventsAutoReply() {
        def actor = system.actor {
            name 'deferred'
            
            onMessage { msg, ctx ->
                if (msg.type == 'async-work') {
                    ctx.deferReply()
                    
                    // Simulate async work
                    Thread.start {
                        Thread.sleep(100)
                        ctx.reply([done: true, result: 'completed'])
                    }
                    
                    // Handler returns null, but no auto-reply should happen
                    return null
                }
            }
        }
        
        def result = actor.askSync([type: 'async-work'], Duration.ofSeconds(2))
        
        assertNotNull(result)
        assertTrue(result.done)
        assertEquals('completed', result.result)
    }
}
