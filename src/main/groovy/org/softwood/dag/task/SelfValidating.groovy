package org.softwood.dag.task

/**
 * 1.4.0: a task that can check its own configuration before the graph runs.
 *
 * <p>{@code TaskGraph.validate()} asks every task that implements this and refuses to start when any
 * of them reports a problem. {@link ControlTask} uses it to compile its rule conditions, so a rule
 * that will not compile stops the graph before anything has run.</p>
 */
interface SelfValidating {

    /**
     * @return one line for each problem, without the task id (the graph adds it); empty when there is none
     */
    List<String> validationProblems()
}
