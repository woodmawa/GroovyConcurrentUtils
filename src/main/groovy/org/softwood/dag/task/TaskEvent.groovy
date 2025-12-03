package org.softwood.dag.task


import java.time.LocalDateTime

class TaskEvent {

    final String taskId
    final TaskState taskState
    final Throwable error   // may be null

    //constructor for ordinary tasks
    TaskEvent(String taskId, TaskState taskState) {
        this(taskId, taskState, null)
    }

    //constructor for tasks that have thrown an error
    TaskEvent(String taskId, TaskState taskState, Throwable error) {
        this.taskId = taskId
        this.taskState = taskState
        this.error = error
    }

    boolean hasError() { return error != null }

    @Override
    String toString() {
        return "TaskEvent(id=$taskId, state=$taskState, error=${error?.message})"
    }
}