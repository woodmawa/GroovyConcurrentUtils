    // Wrap intermediate operations to return Gstream
    Gstream<T> filter(Closure<Boolean> predicate) {
        new Gstream<T>(stream.filter(predicate as Predicate<T>))
    }

// Parallel stream factory methods
static <T> Gstream<T> ofParallel(T... elements) {
    new Gstream<T>(Stream.of(elements).parallel())
}

static <T> Gstream<T> fromParallel(Collection<T> collection) {
    new Gstream<T>(collection.parallelStream())
}

    // Groovy-style alias for filter
    Gstream<T> findAll(Closure<Boolean> predicate) {
        filter(predicate)
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

// Parallel stream factory methods
static <T> Gstream<T> ofParallel(T... elements) {
    new Gstream<T>(Stream.of(elements).parallel())
}

static <T> Gstream<T> fromParallel(Collection<T> collection) {
    new Gstream<T>(collection.parallelStream())
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

    def <R> Gstream<R> map(Closure<R> mapper) {
