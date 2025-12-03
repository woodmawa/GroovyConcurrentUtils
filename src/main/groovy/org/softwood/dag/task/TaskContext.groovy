package org.softwood.dag.task

import org.softwood.config.ConfigLoader
import org.softwood.dag.TaskGraph
import org.softwood.pool.ConcurrentPool
import groovy.util.logging.Slf4j
import org.softwood.pool.WorkerPool

@Slf4j
class TaskContext {

    final Map<String, Object> globals = [:].asSynchronized()
    final Map config
    final WorkerPool pool

    //default empty constructor  - wont have a graph set, create a real ConcurrentPool
    TaskContext() {
        this.config = ConfigLoader.loadConfig()
        this.pool   = new ConcurrentPool("dag-pool")
    }


    // Explicit: inject a custom pool (FakePool or ConcurrentPool)
    TaskContext (WorkerPool pool) {
        this.config = ConfigLoader.loadConfig()
        this.pool   = pool
    }


    TaskContext(Map config) {
        this.config = config ?: ConfigLoader.loadConfig()      // uses  ConfigLoader as fallback
        this.pool   = new ConcurrentPool("dag-pool")    // uses virtual threads if available
    }

    Object get(String key) {
        globals[key]
    }

    void set(String key, Object value) {
        globals[key] = value
    }
}