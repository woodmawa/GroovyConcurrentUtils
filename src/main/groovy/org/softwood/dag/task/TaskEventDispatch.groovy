package org.softwood.dag.task

interface TaskEventDispatch {
    void emit (TaskEvent evt)
}