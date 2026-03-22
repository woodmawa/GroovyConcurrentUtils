package org.softwood.dag.task.spi

import org.softwood.dag.task.DocxTask
import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext

/**
 * SPI provider that creates {@link DocxTask} instances.
 *
 * <p>Type name: {@code "office.docx"} — registered via {@code FlowTypeRegistry}
 * in {@code mcp-agentic-workflow}. The server factory injects {@code docxCallFn}
 * after calling {@link #createTask}.</p>
 *
 * <p>Supported aliases: {@code "docx"}, {@code "word"}, {@code "document"}</p>
 */
class DocxTaskProvider implements ITaskProvider {

    static final String TYPE_NAME = "office.docx"

    @Override
    String getTaskTypeName() { TYPE_NAME }

    @Override
    Class<? extends ITask> getTaskClass() { DocxTask }

    @Override
    DocxTask createTask(String id, String name, TaskContext ctx) {
        new DocxTask(id, name, ctx)
    }

    @Override
    DocxTask createTask(String typeString, String id, String name, TaskContext ctx) {
        createTask(id, name, ctx)
    }

    @Override
    boolean supports(String typeString) {
        typeString in [TYPE_NAME, 'docx', 'word', 'document']
    }

    @Override
    int getPriority() { 10 }

    @Override
    Map<String, Object> getMetadata() {
        [
            description      : "Word document read/generate task via Apache POI",
            effectClass      : "IDEMPOTENT",
            requiresInjection: ["docxCallFn"],
            supportedActions : ["READ", "GENERATE", "APPEND_SECTION", "REPLACE_SECTION", "EXTRACT_TEXT"]
        ]
    }
}
