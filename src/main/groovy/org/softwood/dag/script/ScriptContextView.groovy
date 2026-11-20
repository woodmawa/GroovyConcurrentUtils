package org.softwood.dag.script

import groovy.transform.CompileStatic
import org.softwood.dag.task.TaskContext

/**
 * 1.4.0 (decision D2): what a restricted script is given as {@code ctx} and {@code context}.
 *
 * <p>A read-only view in place of the whole {@link TaskContext}: the graph's globals as they were
 * when the script started, and the id of the task. It has no credentials, no pool, no graph and no
 * way back to the context. A script that has to change globals returns a value for a later task to
 * store, or its task is marked {@code trusted true} and is given the context itself.</p>
 */
@CompileStatic
final class ScriptContextView {

    /** The graph's globals when the script started. Cannot be changed. */
    final Map<String, Object> globals

    /** The id of the task running the script. */
    final String taskId

    ScriptContextView(TaskContext ctx, String taskId) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>()
        if (ctx?.globals != null) copy.putAll(ctx.globals.getAll())
        this.globals = Collections.unmodifiableMap(copy)
        this.taskId = taskId
    }

    /** One global, or null. */
    Object get(String key) {
        return globals.get(key)
    }

    @Override
    String toString() {
        return "ScriptContextView(task=${taskId}, globals=${globals.keySet()})".toString()
    }
}
