package org.softwood.dag.task.spi

import org.softwood.dag.task.ControlTask
import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext

/**
 * SPI provider that creates {@link ControlTask} instances for the type string
 * {@code "control.policy"}.
 *
 * <p>Registered via {@code FlowTypeRegistry} in {@code mcp-agentic-workflow}.
 * The server factory populates {@code rules}, {@code defaultRule}, and
 * {@code inputNodeId} from the FlowDescriptor params after calling
 * {@link #createTask}.</p>
 */
class ControlTaskProvider implements ITaskProvider {

    static final String TYPE_NAME = "control.policy"

    @Override
    String getTaskTypeName() { TYPE_NAME }

    @Override
    Class<? extends ITask> getTaskClass() { ControlTask }

    @Override
    ControlTask createTask(String id, String name, TaskContext ctx) {
        new ControlTask(id, name, ctx)
    }

    @Override
    ControlTask createTask(String typeString, String id, String name, TaskContext ctx) {
        createTask(id, name, ctx)
    }

    @Override
    boolean supports(String typeString) {
        typeString == TYPE_NAME
    }

    @Override
    int getPriority() { 10 }

    @Override
    Map<String, Object> getMetadata() {
        [
            description : "Deterministic policy routing task (no LLM call)",
            effectClass : "PURE",
            requiresConfig: ["rules"]
        ]
    }
}
