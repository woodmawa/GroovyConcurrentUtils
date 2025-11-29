package org.softwood.dag.task


import java.time.LocalDateTime

class TaskEvent {
    String graphId
    String taskId
    TaskEventType type
    LocalDateTime timestamp = LocalDateTime.now()
    Throwable error
    Object result
}