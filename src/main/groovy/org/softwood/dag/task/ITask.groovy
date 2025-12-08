package org.softwood.dag.task

import org.softwood.promise.Promise

/**
 * Contract for executable tasks in the DAG.
 * 
 * Represents tasks that perform work and produce results,
 * such as ServiceTask, ComputeTask, etc.
 */
interface ITask<T> {
    
    // Identity
    String getId()
    String getName()
    
    // State
    TaskState getState()
    boolean getHasStarted()
    boolean isCompleted()
    boolean isSkipped()
    boolean isFailed()
    Throwable getError()
    
    // Topology
    Set<String> getPredecessors()
    Set<String> getSuccessors()
    void dependsOn(String taskId)
    void addSuccessor(String taskId)
    
    // Execution configuration
    Integer getMaxRetries()
    void setMaxRetries(Integer v)
    Long getTimeoutMillis()
    void setTimeoutMillis(Long v)
    
    // State transitions
    void markScheduled()
    void markCompleted()
    void markFailed(Throwable error)
    void markSkipped()
    
    // Data injection (for sharding)
    void setInjectedInput(Object data)
    
    // Execution
    Promise<?> buildPrevPromise(Map<String, ? extends ITask> tasks)
    Promise execute(Promise<?> previousPromise)
    
    // Completion tracking
    Promise<?> getCompletionPromise()
    void setCompletionPromise(Promise<?> promise)
    
    // Event dispatch
    TaskEventDispatch getEventDispatcher()
    void setEventDispatcher(TaskEventDispatch dispatcher)
    
    // Context
    def getCtx()
    void setCtx(def ctx)
}
