
package org.softwood.gstream

import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode

import java.util.function.BinaryOperator
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.function.UnaryOperator
import java.util.stream.Collector
import java.util.stream.Stream
import java.util.stream.Collectors

@CompileStatic
class Gstream<T> {
    private Stream<T> stream

    private Gstream(Stream<T> stream) {
        this.stream = stream
    }

    // Access to underlying stream when needed
    Stream<T> getStream() {
        stream
    }

    // Static builder methods
    static <T> Gstream<T> of(T... elements) {
        new Gstream<T>(Stream.of(elements))
    }

    static <T extends Comparable<T>> Gstream<T> of(Range<T> range) {
        new Gstream<T>(range.stream())
    }

    static <T> Gstream<T> from(Collection<T> collection) {
        new Gstream<T>(collection.stream())
    }

    static <T> Gstream<T> from(Stream<T> stream) {
        new Gstream<T>(stream)
    }

    static <T> Gstream<T> empty() {
        new Gstream<T>(Stream.empty())
    }

    static <T> Gstream<T> generate(Closure<T> supplier) {
        new Gstream<T>(Stream.generate(supplier as Supplier<T>))
    }

    static <T> Gstream<T> iterate(T seed, Closure<T> next) {
        new Gstream<T>(Stream.iterate(seed, next as UnaryOperator<T>))
    }

    // Parallel stream factory methods
    static <T> Gstream<T> ofParallel(T... elements) {
        new Gstream<T>(Stream.of(elements).parallel())
    }

    static <T> Gstream<T> fromParallel(Collection<T> collection) {
        new Gstream<T>(collection.parallelStream())
    }

    // Wrap intermediate operations to return Gstream
    Gstream<T> filter(Closure<Boolean> predicate) {
        new Gstream<T>(stream.filter(predicate as Predicate<T>))
    }

    // Groovy-style alias for filter
    Gstream<T> findAll(Closure<Boolean> predicate) {
        filter(predicate)
    }

    def <R> Gstream<R> map(Closure<R> mapper) {
        new Gstream<R>(stream.map(mapper as Function<T, R>))
    }

    def <R> Gstream<R> flatMap(Closure<Stream<R>> mapper) {
        new Gstream<R>(stream.flatMap(mapper as Function<T, Stream<R>>))
    }

    Gstream<T> distinct() {
        new Gstream<T>(stream.distinct())
    }

    Gstream<T> sorted() {
        new Gstream<T>(stream.sorted())
    }

    Gstream<T> sorted(Closure<Integer> comparator) {
        new Gstream<T>(stream.sorted(comparator as Comparator<T>))
    }

    Gstream<T> peek(Closure<Void> action) {
        new Gstream<T>(stream.peek(action as Consumer<T>))
    }

    Gstream<T> limit(long maxSize) {
        new Gstream<T>(stream.limit(maxSize))
    }

    Gstream<T> skip(long n) {
        new Gstream<T>(stream.skip(n))
    }

    // Parallel/Sequential execution control
    Gstream<T> parallel() {
        new Gstream<T>(stream.parallel())
    }

    Gstream<T> sequential() {
        new Gstream<T>(stream.sequential())
    }

    boolean isParallel() {
        stream.isParallel()
    }

    Gstream<T> takeWhile(Closure<Boolean> predicate) {
        new Gstream<T>(stream.takeWhile(predicate as Predicate<T>))
    }

    Gstream<T> dropWhile(Closure<Boolean> predicate) {
        new Gstream<T>(stream.dropWhile(predicate as Predicate<T>))
    }

    // Terminal operations with Groovy closures
    void forEach(Closure<Void> action) {
        stream.forEach(action as Consumer<T>)
    }

    List<T> toList() {
        stream.collect(Collectors.toList())
    }

    Set<T> toSet() {
        stream.collect(Collectors.toSet())
    }

    T reduce(T identity, Closure<T> accumulator) {
        stream.reduce(identity, accumulator as BinaryOperator<T>)
    }

    Optional<T> reduce(Closure<T> accumulator) {
        stream.reduce(accumulator as BinaryOperator<T>)
    }

    def <R, A> R collect(Collector<? super T, A, R> collector) {
        stream.collect(collector)
    }

    // Common collect operations with closures
    def <K> Map<K, List<T>> groupBy(Closure<K> classifier) {
        stream.collect(Collectors.groupingBy(classifier as Function<T, K>))
    }

    String joining(String delimiter = "") {
        stream.map { it.toString() }.collect(Collectors.joining(delimiter))
    }

    def <R> List<R> collectList(Closure<R> mapper) {
        map(mapper).toList()
    }

    Optional<T> findFirst() {
        stream.findFirst()
    }

    Optional<T> findAny() {
        stream.findAny()
    }

    boolean anyMatch(Closure<Boolean> predicate) {
        stream.anyMatch(predicate as Predicate<T>)
    }

    boolean allMatch(Closure<Boolean> predicate) {
        stream.allMatch(predicate as Predicate<T>)
    }

    boolean noneMatch(Closure<Boolean> predicate) {
        stream.noneMatch(predicate as Predicate<T>)
    }

    long count() {
        stream.count()
    }

    // Fallback to Groovy Collections methods via methodMissing
    // This enables seamless integration with Groovy collection operations
    // Turn off CompileStatic for this method to allow dynamic invocation
    @TypeChecked(TypeCheckingMode.SKIP)
    def methodMissing(String name, Object args) {
        def list = this.toList()
        // Handle both single argument and multiple arguments
        if (args == null) {
            return list."$name"()
        } else if (args instanceof Object[]) {
            Object[] argsArray = (Object[]) args
            if (argsArray.length == 0) {
                return list."$name"()
            } else if (argsArray.length == 1) {
                return list."$name"(argsArray[0])
            } else {
                return list."$name"(*argsArray)
            }
        } else {
            return list."$name"(args)
        }
    }
}