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
                .sorted { a, b -> b <=> a }  // Reverse order
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

        assertEquals(9, result)
    }

    @Test
    void testMethodMissingMin() {
        def result = Gstream.of(5, 2, 8, 1, 9, 3)
                .min()

        assertEquals(1, result)
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
}