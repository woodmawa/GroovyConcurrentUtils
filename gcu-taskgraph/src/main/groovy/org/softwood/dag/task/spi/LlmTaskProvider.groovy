package org.softwood.dag.task.spi

import org.softwood.dag.task.ITask
import org.softwood.dag.task.LlmTask
import org.softwood.dag.task.TaskContext

/**
 * SPI provider that creates {@link LlmTask} instances for the type string
 * {@code "llm.inference"}.
 *
 * <p>Registered via {@code FlowTypeRegistry} in {@code mcp-agentic-workflow}.
 * The server factory is responsible for injecting {@code llmCallFn} after
 * calling {@link #createTask}.</p>
 */
class LlmTaskProvider implements ITaskProvider {

    static final String TYPE_NAME = "llm.inference"

    @Override
    String getTaskTypeName() { TYPE_NAME }

    @Override
    Class<? extends ITask> getTaskClass() { LlmTask }

    @Override
    LlmTask createTask(String id, String name, TaskContext ctx) {
        new LlmTask(id, name, ctx)
    }

    @Override
    LlmTask createTask(String typeString, String id, String name, TaskContext ctx) {
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
            description : "Single-shot LLM inference task via mcp-llm-orchestrator",
            effectClass : "PURE",
            requiresInjection: ["llmCallFn"]
        ]
    }
}
