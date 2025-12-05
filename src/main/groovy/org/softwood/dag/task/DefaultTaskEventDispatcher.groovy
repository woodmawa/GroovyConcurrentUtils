package org.softwood.dag.task

import org.softwood.dag.TaskGraph

class DefaultTaskEventDispatcher implements TaskEventDispatch {

    private final TaskGraph graph

    DefaultTaskEventDispatcher(TaskGraph graph) {
        this.graph = graph
    }

    @Override
    void emit(TaskEvent evt) {
        graph.notifyEvent(evt)
    }
}

