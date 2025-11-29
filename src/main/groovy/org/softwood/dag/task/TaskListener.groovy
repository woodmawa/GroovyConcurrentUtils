package org.softwood.dag.task

interface TaskListener {
    void onEvent(TaskEvent event)
}