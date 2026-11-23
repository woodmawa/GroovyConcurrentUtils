package org.softwood.dag.task.spi

import org.softwood.dag.task.ITask
import org.softwood.dag.task.PptxTask
import org.softwood.dag.task.TaskContext

/**
 * SPI provider that creates {@link PptxTask} instances.
 *
 * <p>Type name: {@code "office.pptx"} — registered via {@code FlowTypeRegistry}
 * in {@code mcp-agentic-workflow}. The server factory injects {@code pptxCallFn}
 * after calling {@link #createTask}.</p>
 *
 * <p>Supported aliases: {@code "pptx"}, {@code "powerpoint"}, {@code "presentation"}</p>
 */
class PptxTaskProvider implements ITaskProvider {

    static final String TYPE_NAME = "office.pptx"

    @Override
    String getTaskTypeName() { TYPE_NAME }

    @Override
    Class<? extends ITask> getTaskClass() { PptxTask }

    @Override
    PptxTask createTask(String id, String name, TaskContext ctx) {
        new PptxTask(id, name, ctx)
    }

    @Override
    PptxTask createTask(String typeString, String id, String name, TaskContext ctx) {
        createTask(id, name, ctx)
    }

    @Override
    boolean supports(String typeString) {
        typeString in [TYPE_NAME, 'pptx', 'powerpoint', 'presentation']
    }

    @Override
    int getPriority() { 10 }

    @Override
    Map<String, Object> getMetadata() {
        [
            description      : "PowerPoint presentation read/generate task via Apache POI",
            effectClass      : "IDEMPOTENT",
            requiresInjection: ["pptxCallFn"],
            supportedActions : ["READ", "GENERATE", "ADD_SLIDE", "EXTRACT_TEXT"]
        ]
    }
}
