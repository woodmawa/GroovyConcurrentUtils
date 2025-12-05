package org.softwood.dag.task

import org.softwood.config.ConfigLoader
import org.softwood.dag.TaskGraph
import org.softwood.pool.ConcurrentPool
import groovy.util.logging.Slf4j
import org.softwood.pool.ExecutorPool
import org.softwood.promise.PromiseFactory
import org.softwood.promise.core.PromisePoolContext
import org.softwood.pool.ExecutorPoolsFactory
import org.softwood.promise.core.dataflow.DataflowPromiseFactory

@Slf4j
class TaskContext {

    final Map<String, Object> globals = [:].asSynchronized()
    final Map config
    final ExecutorPool pool
    final PromiseFactory promiseFactory

    //default empty constructor  - wont have a graph set, create a real ConcurrentPool
    TaskContext() {
        this.promiseFactory = new DataflowPromiseFactory()
        this.config = ConfigLoader.loadConfig()
        this.pool   = ExecutorPoolsFactory.builder()
                .name("dag-pool")
                .build()
    }

    //for testing
    TaskContext(ExecutorPool pool, PromiseFactory promiseFactory) {
        this.config = ConfigLoader.loadConfig()
        this.pool   = pool
        this.promiseFactory = promiseFactory
    }

    // Explicit: inject a custom pool (FakePool or ConcurrentPool)
    TaskContext (ExecutorPool pool) {
        this.promiseFactory = new DataflowPromiseFactory()
        this.config = ConfigLoader.loadConfig()
        this.pool   = pool
    }


    TaskContext(Map config) {
        this.promiseFactory = new DataflowPromiseFactory()
        this.config = config ?: ConfigLoader.loadConfig()      // uses  ConfigLoader as fallback
        this.pool   = ExecutorPoolsFactory.builder()
                .name("dag-pool")
                .build()    // uses virtual threads if available
    }


    // -----------------------
    // 💥 ADD BUILDER HERE
    // -----------------------
    static Builder builder() { new Builder() }

    static class Builder {
        private ExecutorPool pool
        private PromiseFactory promiseFactory
        private Map config

        Builder pool(ExecutorPool p) {
            this.pool = p
            return this
        }

        Builder promiseFactory(PromiseFactory pf) {
            this.promiseFactory = pf
            return this
        }

        Builder config(Map c) {
            this.config = c
            return this
        }

        TaskContext build() {
            // Default config if user doesn't supply one
            Map cfg = config ?: ConfigLoader.loadConfig()

            if (!pool) {
                // Normal default behaviour
                pool = ExecutorPoolsFactory.builder()
                        .name("dag-pool")
                        .build()
            }
            if (!promiseFactory) {
                promiseFactory = new DataflowPromiseFactory()
            }

            // Reuse your existing constructor
            return new TaskContext(pool, promiseFactory)
        }
    }

    Object get(String key) {
        globals[key]
    }

    void set(String key, Object value) {
        globals[key] = value
    }
}