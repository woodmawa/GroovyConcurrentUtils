package org.softwood.dag.task

/**
 * Contract for decision/routing tasks in the DAG.
 * 
 * Represents tasks that make routing decisions and control flow,
 * such as ConditionalForkTask, DynamicRouterTask, ShardingRouterTask, etc.
 */
interface IDecisionTask<T> extends ITask<T> {
    
    // Routing-specific state
    Set<String> getTargetIds()
    boolean getAlreadyRouted()
    void setAlreadyRouted(boolean value)
    List<String> getLastSelectedTargets()
    void setLastSelectedTargets(List<String> targets)
    
    // Sharding-specific (only used by ShardingRouterTask)
    boolean getScheduledShardSuccessors()
    void setScheduledShardSuccessors(boolean value)
}
