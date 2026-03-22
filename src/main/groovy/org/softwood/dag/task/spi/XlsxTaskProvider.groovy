package org.softwood.dag.task.spi

import org.softwood.dag.task.ITask
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.XlsxTask

/**
 * SPI provider that creates {@link XlsxTask} instances.
 *
 * <p>Type name: {@code "office.xlsx"} — registered via {@code FlowTypeRegistry}
 * in {@code mcp-agentic-workflow}. The server factory injects {@code xlsxCallFn}
 * (and optionally {@code llmCallFn}) after calling {@link #createTask}.</p>
 *
 * <p>Supported aliases: {@code "xlsx"}, {@code "spreadsheet"}, {@code "excel"}</p>
 */
class XlsxTaskProvider implements ITaskProvider {

    static final String TYPE_NAME = "office.xlsx"

    @Override
    String getTaskTypeName() { TYPE_NAME }

    @Override
    Class<? extends ITask> getTaskClass() { XlsxTask }

    @Override
    XlsxTask createTask(String id, String name, TaskContext ctx) {
        new XlsxTask(id, name, ctx)
    }

    @Override
    XlsxTask createTask(String typeString, String id, String name, TaskContext ctx) {
        createTask(id, name, ctx)
    }

    @Override
    boolean supports(String typeString) {
        typeString in [TYPE_NAME, 'xlsx', 'spreadsheet', 'excel']
    }

    @Override
    int getPriority() { 10 }

    @Override
    Map<String, Object> getMetadata() {
        [
            description      : "Excel workbook read/write task via Apache POI",
            effectClass      : "IDEMPOTENT",
            requiresInjection: ["xlsxCallFn"],
            optionalInjection: ["llmCallFn"],
            supportedActions : ["READ", "WRITE", "APPEND", "TRANSFORM", "CREATE"]
        ]
    }
}
