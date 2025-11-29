package org.softwood.dag.task

import org.softwood.config.ConfigLoader
import org.softwood.pool.ConcurrentPool
import groovy.util.logging.Slf4j

@Slf4j
class TaskContext {

    final Map<String, Object> globals = [:].asSynchronized()
    final Map config
    final ConcurrentPool pool

    TaskContext(Map config = null, ConcurrentPool pool = null) {
        this.config = config ?: ConfigLoader.loadConfig()      // uses your loader
        this.pool   = pool   ?: new ConcurrentPool("dag-pool") // uses virtual threads if available
    }

    Object get(String key) {
        globals[key]
    }

    void set(String key, Object value) {
        globals[key] = value
    }
}