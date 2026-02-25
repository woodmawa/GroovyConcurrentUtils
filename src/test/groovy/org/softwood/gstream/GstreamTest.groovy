package org.softwood.gstream

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class GstreamTest {

    @Test
    void testBasicFilterAndMap() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .filter { it % 2 == 0 }
                .map { it * 2 }
                .toList()

        assertEquals([4, 8], result)
    }

    @Test
    void testFindAll() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .findAll { it > 2 }
                .toList()

        assertEquals([3, 4, 5], result)
    }

    @Test
    void testFromCollection() {
        def list = [1, 2, 3, 4, 5]
        def result = Gstream.from(list)
                .filter { it % 2 != 0 }
                .toList()

        assertEquals([1, 3, 5], result)
    }

    @Test
    void testFromRange() {
        def result = Gstream.of(1..10)
                .filter { it % 2 == 0 }
                .toList()

        assertEquals([2, 4, 6, 8, 10], result)
    }

    @Test
    void testFromStream() {
        def javaStream = [1, 2, 3].stream()
        def result = Gstream.from(javaStream)
                .map { it * 10 }
                .toList()

        assertEquals([10, 20, 30], result)
    }

    @Test
    void testEmpty() {
        def result = Gstream.empty()
                .toList()

        assertEquals([], result)
    }

    @Test
    void testGenerate() {
        def counter = 0
        def result = Gstream.generate { ++counter }
                .limit(5)
                .toList()

        assertEquals([1, 2, 3, 4, 5], result)
    }

    @Test
    void testIterate() {
        def result = Gstream.iterate(1) { it * 2 }
                .limit(5)
                .toList()

        assertEquals([1, 2, 4, 8, 16], result)
    }

    @Test
    void testDistinct() {
        def result = Gstream.of(1, 2, 2, 3, 3, 3, 4)
                .distinct()
                .toList()

        assertEquals([1, 2, 3, 4], result)
    }

    @Test
    void testSorted() {
        def result = Gstream.of(5, 2, 8, 1, 9)
                .sorted()
                .toList()

        assertEquals([1, 2, 5, 8, 9], result)
    }

    @Test
    void testSortedWithComparator() {
        def result = Gstream.of(5, 2, 8, 1, 9)
                .sorted { a, b -> b <=> a }
                .toList()

        assertEquals([9, 8, 5, 2, 1], result)
    }

    @Test
    void testPeek() {
        def peeked = []
        def result = Gstream.of(1, 2, 3)
                .peek { peeked << it }
                .map { it * 2 }
                .toList()

        assertEquals([1, 2, 3], peeked)
        assertEquals([2, 4, 6], result)
    }

    @Test
    void testLimit() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .limit(3)
                .toList()

        assertEquals([1, 2, 3], result)
    }

    @Test
    void testSkip() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .skip(2)
                .toList()

        assertEquals([3, 4, 5], result)
    }

    @Test
    void testTakeWhile() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .takeWhile { it < 4 }
                .toList()

        assertEquals([1, 2, 3], result)
    }

    @Test
    void testDropWhile() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .dropWhile { it < 3 }
                .toList()

        assertEquals([3, 4, 5], result)
    }

    @Test
    void testFlatMap() {
        def matrix = [[1, 2], [3, 4], [5, 6]]
        def result = Gstream.from(matrix)
                .flatMap { row -> row.stream() }
                .filter { it > 2 }
                .toList()

        assertEquals([3, 4, 5, 6], result)
    }

    @Test
    void testToSet() {
        def result = Gstream.of(1, 2, 2, 3, 3, 3)
                .toSet()

        assertEquals([1, 2, 3] as Set, result)
    }

    @Test
    void testForEach() {
        def collected = []
        Gstream.of(1, 2, 3)
                .forEach { collected << it * 2 }

        assertEquals([2, 4, 6], collected)
    }

    @Test
    void testReduceWithIdentity() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .reduce(0) { acc, val -> acc + val }

        assertEquals(15, result)
    }

    @Test
    void testReduceWithoutIdentity() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .reduce { acc, val -> acc + val }

        assertTrue(result.isPresent())
        assertEquals(15, result.get())
    }

    @Test
    void testGroupBy() {
        def result = Gstream.of("apple", "apricot", "banana", "blueberry", "cherry")
                .groupBy { it[0] }

        assertEquals(['apple', 'apricot'], result['a'])
        assertEquals(['banana', 'blueberry'], result['b'])
        assertEquals(['cherry'], result['c'])
    }

    @Test
    void testJoining() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .joining(", ")

        assertEquals("1, 2, 3, 4, 5", result)
    }

    @Test
    void testCollectList() {
        def result = Gstream.of(1, 2, 3)
                .collectList { it * 10 }

        assertEquals([10, 20, 30], result)
    }

    @Test
    void testFindFirst() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .filter { it > 2 }
                .findFirst()

        assertTrue(result.isPresent())
        assertEquals(3, result.get())
    }

    @Test
    void testFindAny() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .filter { it > 2 }
                .findAny()

        assertTrue(result.isPresent())
        assertTrue(result.get() in [3, 4, 5])
    }

    @Test
    void testAnyMatch() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .anyMatch { it > 3 }

        assertTrue(result)
    }

    @Test
    void testAllMatch() {
        def result = Gstream.of(2, 4, 6, 8)
                .allMatch { it % 2 == 0 }

        assertTrue(result)
    }

    @Test
    void testNoneMatch() {
        def result = Gstream.of(1, 3, 5, 7)
                .noneMatch { it % 2 == 0 }

        assertTrue(result)
    }

    @Test
    void testCount() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .filter { it > 2 }
                .count()

        assertEquals(3L, result)
    }

    @Test
    void testParallelStreamCreation() {
        def result = Gstream.ofParallel(1, 2, 3, 4, 5)
                .filter { it % 2 == 0 }
                .toList()

        assertEquals([2, 4], result.sort())
    }

    @Test
    void testFromParallel() {
        def result = Gstream.fromParallel([1, 2, 3, 4, 5])
                .map { it * 2 }
                .toList()

        assertEquals([2, 4, 6, 8, 10], result.sort())
    }

    @Test
    void testParallelConversion() {
        def stream = Gstream.of(1..100)
                .parallel()

        assertTrue(stream.isParallel())

        def result = stream.filter { it % 2 == 0 }
                .toList()

        assertEquals(50, result.size())
    }

    @Test
    void testSequentialConversion() {
        def stream = Gstream.ofParallel(1, 2, 3, 4, 5)
                .sequential()

        assertFalse(stream.isParallel())
    }

    @Test
    void testIsParallel() {
        def sequential = Gstream.of(1, 2, 3)
        assertFalse(sequential.isParallel())

        def parallel = Gstream.ofParallel(1, 2, 3)
        assertTrue(parallel.isParallel())
    }

    @Test
    void testParallelWithRange() {
        def result = Gstream.of(1..1000)
                .parallel()
                .filter { it % 2 == 0 }
                .count()

        assertEquals(500L, result)
    }

    @Test
    void testMethodMissingSum() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .filter { it % 2 == 0 }
                .sum()

        assertEquals(6, result)
    }

    @Test
    void testMethodMissingReverse() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .reverse()

        assertEquals([5, 4, 3, 2, 1], result)
    }

    @Test
    void testMethodMissingFirst() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .filter { it > 2 }
                .first()

        assertEquals(3, result)
    }

    @Test
    void testMethodMissingLast() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .filter { it > 2 }
                .last()

        assertEquals(5, result)
    }

    @Test
    void testMethodMissingTake() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .take(3)

        assertEquals([1, 2, 3], result)
    }

    @Test
    void testMethodMissingMax() {
        def result = Gstream.of(5, 2, 8, 1, 9, 3)
                .max()

        assertTrue(result.isPresent())
        assertEquals(9, result.get())
    }

    @Test
    void testMethodMissingMin() {
        def result = Gstream.of(5, 2, 8, 1, 9, 3)
                .min()

        assertTrue(result.isPresent())
        assertEquals(1, result.get())
    }

    @Test
    void testMethodMissingUnique() {
        def result = Gstream.of(1, 2, 2, 3, 3, 3, 4)
                .unique()

        assertEquals([1, 2, 3, 4], result)
    }

    @Test
    void testComplexPipeline() {
        def people = [
                [name: 'Alice', age: 30],
                [name: 'Bob', age: 25],
                [name: 'Charlie', age: 35],
                [name: 'David', age: 28]
        ]

        def result = Gstream.from(people)
                .filter { it.age > 26 }
                .map { it.name }
                .sorted()
                .toList()

        assertEquals(['Alice', 'Charlie', 'David'], result)
    }

    @Test
    void testChainedOperations() {
        def result = Gstream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                .filter { it % 2 == 0 }
                .map { it * 3 }
                .filter { it > 10 }
                .sorted()
                .toList()

        assertEquals([12, 18, 24, 30], result)
    }

    @Test
    void testGetStream() {
        def gstream = Gstream.of(1, 2, 3)
        def javaStream = gstream.getStream()

        assertNotNull(javaStream)
        assertTrue(javaStream instanceof java.util.stream.Stream)
    }

    // New feature tests

    @Test
    void testMaxWithComparator() {
        def result = Gstream.of(5, 2, 8, 1, 9, 3)
                .max { a, b -> a <=> b }

        assertTrue(result.isPresent())
        assertEquals(9, result.get())
    }

    @Test
    void testMinWithComparator() {
        def result = Gstream.of(5, 2, 8, 1, 9, 3)
                .min { a, b -> a <=> b }

        assertTrue(result.isPresent())
        assertEquals(1, result.get())
    }

    @Test
    void testMaxWithComparatorEmpty() {
        def result = Gstream.empty()
                .max { a, b -> a <=> b }

        assertFalse(result.isPresent())
    }

    @Test
    void testPartitioningBy() {
        def result = Gstream.of(1, 2, 3, 4, 5, 6)
                .partitioningBy { it % 2 == 0 }

        assertEquals([1, 3, 5], result[false])
        assertEquals([2, 4, 6], result[true])
    }

    @Test
    void testToMapWithKeyAndValueMappers() {
        def people = [
                [name: 'Alice', age: 30],
                [name: 'Bob', age: 25],
                [name: 'Charlie', age: 35]
        ]

        def result = Gstream.from(people)
                .toMap({ it.name }, { it.age })

        assertEquals(30, result['Alice'])
        assertEquals(25, result['Bob'])
        assertEquals(35, result['Charlie'])
    }

    @Test
    void testToMapWithKeyMapperOnly() {
        def words = ["apple", "cherry", "kiwi"]
        def result = Gstream.from(words)
                .toMap { it.length() }

        assertEquals("apple", result[5])
        assertEquals("cherry", result[6])
        assertEquals("kiwi", result[4])
    }

    @Test
    void testToMapWithMergeFunction() {
        def items = [
                [category: 'A', value: 10],
                [category: 'B', value: 20],
                [category: 'A', value: 30]
        ]

        def result = Gstream.from(items)
                .toMap(
                        { it.category },
                        { it.value },
                        { a, b -> a + b }
                )

        assertEquals(40, result['A'])
        assertEquals(20, result['B'])
    }

    @Test
    void testToCollection() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .toCollection { new LinkedHashSet() }

        assertTrue(result instanceof LinkedHashSet)
        assertEquals([1, 2, 3, 4, 5] as Set, result)
    }

    @Test
    void testToArray() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .toArray()

        assertTrue(result instanceof Object[])
        assertEquals(5, result.length)
        assertEquals([1, 2, 3, 4, 5], result as List)
    }

    @Test
    void testConcat() {
        def stream1 = Gstream.of(1, 2, 3)
        def stream2 = Gstream.of(4, 5, 6)
        def result = Gstream.concat(stream1, stream2)
                .toList()

        assertEquals([1, 2, 3, 4, 5, 6], result)
    }

    @Test
    void testConcatWithOperations() {
        def stream1 = Gstream.of(1, 2, 3)
        def stream2 = Gstream.of(4, 5, 6)
        def result = Gstream.concat(stream1, stream2)
                .filter { it % 2 == 0 }
                .map { it * 10 }
                .toList()

        assertEquals([20, 40, 60], result)
    }

    @Test
    void testUnordered() {
        def result = Gstream.of(1, 2, 3, 4, 5)
                .unordered()
                .distinct()
                .toList()

        assertEquals(5, result.size())
        assertTrue(result.containsAll([1, 2, 3, 4, 5]))
    }

    @Test
    void testOnCloseAndClose() {
        def closeHandlerCalled = false
        def stream = Gstream.of(1, 2, 3)
                .onClose { closeHandlerCalled = true }

        assertFalse(closeHandlerCalled)

        stream.toList()
        stream.close()

        assertTrue(closeHandlerCalled)
    }

    @Test
    void testMultipleCloseHandlers() {
        def handlers = []
        def stream = Gstream.of(1, 2, 3)
                .onClose { handlers << 'first' }
                .onClose { handlers << 'second' }
                .onClose { handlers << 'third' }

        stream.toList()
        stream.close()

        assertEquals(['first', 'second', 'third'], handlers)
    }

    @Test
    void testBuilderBasic() {
        def builder = Gstream.builder()
        builder.add(1)
        builder.add(2)
        builder.add(3, 4, 5)

        def result = builder.build()
                .toList()

        assertEquals([1, 2, 3, 4, 5], result)
    }

    @Test
    void testBuilderWithOperations() {
        def builder = Gstream.builder()
        builder.add(1, 2, 3, 4, 5, 6)
        builder.filter { it % 2 == 0 }
        builder.map { it * 10 }

        def result = builder.build()
                .toList()

        assertEquals([20, 40, 60], result)
    }

    @Test
    void testBuilderWithFrom() {
        def builder = Gstream.builder()
        builder.from([10, 20, 30, 40, 50])
        builder.filter { it > 25 }

        def result = builder.build()
                .toList()

        assertEquals([30, 40, 50], result)
    }

    @Test
    void testBuilderWithParallel() {
        def builder = Gstream.builder()
        builder.from(1..100)
        builder.parallel(true)

        def stream = builder.build()

        assertTrue(stream.isParallel())
    }

    @Test
    void testBuilderChaining() {
        def result = Gstream.builder()
                .add(1, 2, 3, 4, 5)
                .filter { it > 2 }
                .map { it * 2 }
                .sorted()
                .build()
                .toList()

        assertEquals([6, 8, 10], result)
    }

    @Test
    void testDSLBasic() {
        def result = Gstream.build {
            add 1, 2, 3, 4, 5
        }.toList()

        assertEquals([1, 2, 3, 4, 5], result)
    }

    @Test
    void testDSLWithOperations() {
        def result = Gstream.build {
            add 1, 2, 3, 4, 5, 6
            filter { it % 2 == 0 }
            map { it * 10 }
        }.toList()

        assertEquals([20, 40, 60], result)
    }

    @Test
    void testDSLWithFrom() {
        def result = Gstream.build {
            from([10, 20, 30, 40, 50])
            filter { it > 25 }
            sorted()
        }.toList()

        assertEquals([30, 40, 50], result)
    }

    @Test
    void testDSLWithRange() {
        def result = Gstream.build {
            from(1..10)
            filter { it % 2 == 0 }
        }.toList()

        assertEquals([2, 4, 6, 8, 10], result)
    }

    @Test
    void testDSLWithParallel() {
        def result = Gstream.build {
            from(1..100)
            parallel true
            filter { it % 2 == 0 }
        }.count()

        assertEquals(50L, result)
    }

    @Test
    void testDSLComplexPipeline() {
        def result = Gstream.build {
            from(1..20)
            filter { it > 5 }
            map { it * 2 }
            filter { it < 30 }
            distinct()
            sorted()
            limit 5
        }.toList()

        assertEquals([12, 14, 16, 18, 20], result)
    }

    @Test
    void testDSLWithOf() {
        def result = Gstream.build {
            of 1, 2, 3, 4, 5
            map { it * 2 }
        }.toList()

        assertEquals([2, 4, 6, 8, 10], result)
    }

    @Test
    void testDSLNestedOperations() {
        def result = Gstream.build {
            add 1, 2, 3, 4, 5, 6, 7, 8, 9, 10
            filter { it % 2 == 0 }
            map { it * it }
            filter { it > 20 }
            sorted { a, b -> b <=> a }
            limit 3
        }.toList()

        assertEquals([100, 64, 36], result)
    }

    @Test
    void testBuilderWithSkipAndLimit() {
        def result = Gstream.builder()
                .from(1..10)
                .skip(3)
                .limit(5)
                .build()
                .toList()

        assertEquals([4, 5, 6, 7, 8], result)
    }

    @Test
    void testDSLWithDistinctAndSorted() {
        def result = Gstream.build {
            add 5, 2, 8, 2, 1, 9, 1, 3
            distinct()
            sorted()
        }.toList()

        assertEquals([1, 2, 3, 5, 8, 9], result)
    }

    @Test
    void testFlatMapIterable() {
        def result = Gstream.of(1, 2, 3)
                .flatMapIterable { [it, it * 10] }
                .toList()

        assertEquals([1, 10, 2, 20, 3, 30], result)
    }

    @Test
    void testSortedBy() {
        def people = [
                [name: 'Bob', age: 30],
                [name: 'Alice', age: 25],
                [name: 'Charlie', age: 35]
        ]

        def result = Gstream.from(people)
                .sortedBy { it.age }
                .map { it.name }
                .toList()

        assertEquals(['Alice', 'Bob', 'Charlie'], result)
    }

    @Test
    void testDistinctBy() {
        def words = ["apple", "apricot", "banana", "blueberry"]
        def result = Gstream.from(words)
                .distinctBy { it[0] }
                .toList()

        assertEquals(['apple', 'banana'], result)
    }

    @Test
    void testMaxBy() {
        def result = Gstream.of([name:'Bob',age:25], [name:'Alice',age:30], [name:'Dan',age:28])
                .maxBy { it.age }

        assertTrue(result.isPresent())
        assertEquals('Alice', result.get().name)
    }

    @Test
    void testMinBy() {
        def result = Gstream.of([name:'Bob',age:25], [name:'Alice',age:30], [name:'Dan',age:28])
                .minBy { it.age }

        assertTrue(result.isPresent())
        assertEquals('Bob', result.get().name)
    }

    @Test
    void testZipWithIndex() {
        def result = Gstream.of('a', 'b', 'c')
                .zipWithIndex()
                .toList()

        assertEquals([(0L):'a',(1L):'b',(2L):'c'], result.collectEntries { [(it.v1):it.v2] })
    }

    @Test
    void testZip() {
        def s1 = Gstream.of(1, 2, 3)
        def s2 = Gstream.of('a', 'b', 'c')

        def result = s1.zip(s2).toList()

        assertEquals([[1,'a'], [2,'b'], [3,'c']], result)
    }

    @Test
    void testChunked() {
        def result = Gstream.of(1,2,3,4,5,6,7)
                .chunked(3)
                .toList()

        assertEquals([[1,2,3], [4,5,6], [7]], result)
    }

    @Test
    void testWindowed() {
        def result = Gstream.of(1,2,3,4,5)
                .windowed(3)
                .toList()

        assertEquals([[1,2,3], [2,3,4], [3,4,5]], result)
    }

    @Test
    void testTee() {
        def seen = []
        def result = Gstream.of(1, 2, 3)
                .tee { seen << it }
                .map { it * 2 }
                .toList()

        assertEquals([1,2,3], seen)
        assertEquals([2,4,6], result)
    }

    @Test
    void testMapMulti() {
        def result = Gstream.of(1, 2, 3)
                .mapMulti { value, consumer ->
                    consumer.accept(value)
                    consumer.accept(value * 10)
                }
                .toList()

        assertEquals([1,10,2,20,3,30], result)
    }

    @Test
    void testOnErrorContinue() {
        def result = Gstream.of("10", "x", "30")
                .map { it.toInteger() }
                .onErrorContinue { ex, value -> /* ignore */ }
                .toList()

        assertEquals([10, 30], result)
    }

    @Test
    void testSummarizingInt() {
        def stats = Gstream.of(1, 2, 3, 4, 5).summarizingInt { it }

        assertEquals(5, stats.count)
        assertEquals(1, stats.min)
        assertEquals(5, stats.max)
        assertEquals(15, stats.sum)
    }

    @Test
    void testBuilderWithDistinctBy() {
        def builder = Gstream.builder()
        builder.add 'apple', 'apricot', 'banana', 'blueberry'
        builder.distinctBy { it[0] }

        def result = builder.build().toList()

        assertEquals(['apple', 'banana'], result)
    }

    @Test
    void testBuilderFlatMapIterable() {
        def builder = Gstream.builder()
        builder.add 1,2,3
        builder.flatMapIterable { [it, it*10] }

        def result = builder.build().toList()

        assertEquals([1,10,2,20,3,30], result)
    }

    @Test
    void testBuilderSortedBy() {
        def builder = Gstream.builder()
        builder.add([n:'Bob',a:30], [n:'Alice',a:20], [n:'Charlie',a:40])
        builder.sortedBy { it.a }

        def result = builder.build().map{it.n}.toList()

        assertEquals(['Alice','Bob','Charlie'], result)
    }

    // -------------------------------------------------------------------------
    // Hardening tests (v2.1)
    // -------------------------------------------------------------------------

    // --- distinctBy: parallel safety ---

    @Test
    void testDistinctByParallel() {
        // Run distinctBy on a parallel stream - must not produce duplicates
        // (plain HashSet would be a data race; ConcurrentHashMap.newKeySet() is safe)
        def words = (1..1000).collect { "key${it % 50}" }  // 50 distinct keys repeated 20x
        def result = Gstream.fromParallel(words)
                .distinctBy { it }
                .toList()
        assertEquals(50, result.size())
        assertEquals(50, result.toSet().size())
    }

    @Test
    void testDistinctByNullKeyExtractorThrows() {
        def ex = assertThrows(NullPointerException) {
            Gstream.of(1, 2, 3).distinctBy(null)
        }
        assertTrue(ex.message.contains("keyExtractor"))
    }

    // --- windowed: off-by-one fix ---

    @Test
    void testWindowedCorrectCount() {
        // [1..5] windowed(3) => [[1,2,3],[2,3,4],[3,4,5]] — exactly 3 windows
        def result = Gstream.of(1, 2, 3, 4, 5)
                .windowed(3)
                .toList()
        assertEquals(3, result.size())
        assertEquals([1, 2, 3], result[0])
        assertEquals([2, 3, 4], result[1])
        assertEquals([3, 4, 5], result[2])
    }

    @Test
    void testWindowedSizeEqualsStreamLength() {
        // window size == stream size => exactly 1 window
        def result = Gstream.of(1, 2, 3)
                .windowed(3)
                .toList()
        assertEquals(1, result.size())
        assertEquals([1, 2, 3], result[0])
    }

    @Test
    void testWindowedSizeLargerThanStream() {
        // window size > stream => no windows
        def result = Gstream.of(1, 2)
                .windowed(5)
                .toList()
        assertEquals(0, result.size())
    }

    @Test
    void testWindowedSizeOne() {
        // window size 1 => each element is its own window
        def result = Gstream.of(1, 2, 3)
                .windowed(1)
                .toList()
        assertEquals(3, result.size())
        assertEquals([1], result[0])
        assertEquals([2], result[1])
        assertEquals([3], result[2])
    }

    // --- onErrorContinue: guard against infinite loop ---

    @Test
    void testOnErrorContinueBoundedErrors() {
        // Every single element throws -- should hit the MAX_CONSECUTIVE_ERRORS guard
        // (1000) and rethrow as IllegalStateException rather than looping forever.
        // We need more than 1000 elements so the guard can trip.
        def data = (1..1100).toList()
        def stream = Gstream.from(data)
                .map { throw new RuntimeException("always fails") }
                .onErrorContinue { ex, val -> /* swallow */ }
        assertThrows(IllegalStateException) {
            stream.toList()
        }
    }

    @Test
    void testOnErrorContinueResetsCounterOnSuccess() {
        // Error then success then error: counter resets on success, no false positive
        def errors = []
        def result = Gstream.of("1", "x", "2", "y", "3")
                .map { it.toInteger() }
                .onErrorContinue { ex, val -> errors << val }
                .toList()
        assertEquals([1, 2, 3], result)
        assertEquals(2, errors.size())  // "x" and "y" both null (upstream parse failure)
    }

    @Test
    void testOnErrorContinueNullHandlerThrows() {
        def ex = assertThrows(NullPointerException) {
            Gstream.of(1, 2, 3).onErrorContinue(null)
        }
        assertTrue(ex.message.contains("handler"))
    }

    // --- tee and mapMulti null-guard ---

    @Test
    void testTeeNullHandlerThrows() {
        def ex = assertThrows(NullPointerException) {
            Gstream.of(1, 2, 3).tee(null)
        }
        assertTrue(ex.message.contains("sideEffect"))
    }

    @Test
    void testMapMultiNullMapperThrows() {
        def ex = assertThrows(NullPointerException) {
            Gstream.of(1, 2, 3).mapMulti(null)
        }
        assertTrue(ex.message.contains("mapper"))
    }

    // --- executeWith pool integration ---

    @Test
    void testExecuteWithPool() {
        def pool = new org.softwood.pool.ConcurrentPool("test-gstream")
        try {
            def result = Gstream.of(1, 2, 3, 4, 5)
                    .parallel()
                    .executeWith(pool) { gs ->
                        gs.filter { it % 2 == 0 }.toList()
                    }
            assertEquals([2, 4], result.sort())
        } finally {
            pool.shutdown()
        }
    }

    @Test
    void testExecuteWithClosedPoolThrows() {
        def pool = new org.softwood.pool.ConcurrentPool("test-closed")
        pool.shutdown()
        assertThrows(IllegalStateException) {
            Gstream.of(1, 2, 3).executeWith(pool) { gs -> gs.toList() }
        }
    }

    @Test
    void testExecuteWithNullPoolThrows() {
        assertThrows(NullPointerException) {
            Gstream.of(1, 2, 3).executeWith(null) { gs -> gs.toList() }
        }
    }
}