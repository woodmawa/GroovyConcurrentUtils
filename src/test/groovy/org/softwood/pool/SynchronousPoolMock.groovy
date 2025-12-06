package org.softwood.pool


import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fully synchronous deterministic mock ExecutorPool.
 * Executes all tasks immediately in the calling thread.
 */
class SynchronousPoolMock implements ExecutorPool {

    private final AtomicBoolean closed = new AtomicBoolean(false)
    String name = "SyncPool"
    final boolean usingVirtualThreads = false

    // trivial single-thread executors, but never actually used asynchronously
    private final ExecutorService executor = new DirectExecutorService()
    private final ScheduledExecutorService scheduler = new DirectScheduledExecutorService()

    SynchronousPoolMock(name = null) {
        this.name = name ?: "SyncPool"
    }

    @Override
    ExecutorService getExecutor() {
        if (closed.get()) throw new IllegalStateException("Pool closed")
        return executor
    }

    @Override
    ScheduledExecutorService getScheduledExecutor() {
        return scheduler
    }

    // ------------------------------
    // Sync execution versions
    // ------------------------------

    @Override
    CompletableFuture execute(Closure task) {
        ensureOpen()
        return completeSync(task.call())
    }

    @Override
    CompletableFuture execute(Closure task, Object[] args) {
        ensureOpen()
        return completeSync(task.call(args))
    }

    @Override
    boolean tryExecute(Closure task) {
        if (closed.get()) return false
        task.call()
        return true
    }

    @Override
    CompletableFuture execute(Callable task) {
        ensureOpen()
        return completeSync(task.call())
    }

    @Override
    CompletableFuture execute(Runnable task) {
        ensureOpen()
        task.run()
        return completeSync(null)
    }

    // ------------------------------
    // Scheduled – execute immediately
    // ------------------------------

    @Override
    ScheduledFuture scheduleExecution(int delay, TimeUnit unit, Closure task) {
        ensureOpen()
        task.call()
        return DirectScheduledFuture.completed()
    }

    @Override
    ScheduledFuture scheduleWithFixedDelay(int initialDelay, int delay, TimeUnit unit, Closure task) {
        ensureOpen()
        task.call()
        return DirectScheduledFuture.completed()
    }

    @Override
    ScheduledFuture scheduleAtFixedRate(int initialDelay, int period, TimeUnit unit, Closure task) {
        ensureOpen()
        task.call()
        return DirectScheduledFuture.completed()
    }

    // ------------------------------

    @Override boolean isClosed() { closed.get() }
    @Override String getName() { name }
    @Override boolean isUsingVirtualThreads() { false }

    @Override
    void shutdown() {
        closed.set(true)

    }

    private static CompletableFuture completeSync(Object result) {
        def cf = new CompletableFuture()
        cf.complete(result)
        return cf
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Pool is closed")
    }

    // ------------------------------
    // Minimal direct executor impls
    // ------------------------------

    static class DirectExecutorService extends AbstractExecutorService {
        boolean shutdown = false

        @Override void shutdown() { shutdown = true }
        @Override List<Runnable> shutdownNow() { shutdown = true; return [] }
        @Override boolean isShutdown() { shutdown }
        @Override boolean isTerminated() { shutdown }
        @Override boolean awaitTermination(long timeout, TimeUnit unit) { shutdown }
        @Override void execute(Runnable command) { command.run() }
    }

    static class DirectScheduledExecutorService extends DirectExecutorService implements ScheduledExecutorService {
        @Override ScheduledFuture schedule(Runnable command, long delay, TimeUnit unit) { command.run(); DirectScheduledFuture.completed() }
        @Override <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return DirectScheduledFuture.completed(callable.call())
        }
        @Override ScheduledFuture scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            command.run(); DirectScheduledFuture.completed()
        }
        @Override ScheduledFuture scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            command.run(); DirectScheduledFuture.completed()
        }
    }

    static class DirectScheduledFuture<V> implements ScheduledFuture<V> {
        final V value

        DirectScheduledFuture(V v) { this.value = v }

        static <T> DirectScheduledFuture<T> completed(T v = null) {
            return new DirectScheduledFuture<T>(v)
        }

        @Override long getDelay(TimeUnit unit) { 0 }
        @Override int compareTo(Delayed o) { 0 }
        @Override boolean cancel(boolean mayInterruptIfRunning) { false }
        @Override boolean isCancelled() { false }
        @Override boolean isDone() { true }
        @Override V get() { value }
        @Override V get(long timeout, TimeUnit unit) { value }
    }
}