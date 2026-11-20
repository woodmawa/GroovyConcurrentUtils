package org.softwood.dag.task

import groovy.transform.CompileStatic

/**
 * 1.4.0: what a failed task says about retrying, carried on the exception it failed with.
 *
 * <p>The same facts are in the metadata of the task's FAILED event ({@link TaskEvent#WILL_RETRY},
 * {@link TaskEvent#NOT_RETRIED_BECAUSE}). A caller that only has the exception, because it awaited
 * the graph's promise and listened to no events, reads them here:</p>
 * <pre>
 *   try {
 *       graph.start().get()
 *   } catch (Exception e) {
 *       TaskFailureSignal signal = TaskFailureSignal.of(e)
 *       if (signal?.permanent) { ... do not run it again unchanged ... }
 *   }
 * </pre>
 *
 * <p>The exception a task throws keeps its type: nothing is wrapped. The signal travels as a
 * suppressed exception of the one thrown, which is why it is itself a Throwable. It has no stack
 * trace and is never thrown.</p>
 */
@CompileStatic
final class TaskFailureSignal extends RuntimeException {

    /** The task that failed. */
    final String taskId

    /** One of {@link TaskEvent#NOT_RETRIABLE}, {@link TaskEvent#NO_RETRIES_CONFIGURED}, {@link TaskEvent#RETRIES_EXHAUSTED}. */
    final String notRetriedBecause

    /** How many times the task was attempted. */
    final int attempts

    private TaskFailureSignal(String taskId, String notRetriedBecause, int attempts) {
        super(("task '${taskId}' failed and will not be retried: ${notRetriedBecause} (attempts: ${attempts})").toString(),
                null, false, false)
        this.taskId = taskId
        this.notRetriedBecause = notRetriedBecause
        this.attempts = attempts
    }

    /** Always false: the signal exists only on a failure that is final for the task's retry policy. */
    boolean isWillRetry() {
        return false
    }

    /**
     * True when another attempt cannot fix the failure (a refusal, a bad configuration, a timeout).
     * Do not re-run the task, or a scope that contains it, unchanged.
     */
    boolean isPermanent() {
        return notRetriedBecause == TaskEvent.NOT_RETRIABLE
    }

    /**
     * The signal on this failure, looking through causes and suppressed exceptions; null when no
     * task marked it. With several, the first found: the outermost exception's own, then its causes'.
     */
    static TaskFailureSignal of(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>())
        Throwable t = failure
        while (t != null && seen.add(t)) {
            if (t instanceof TaskFailureSignal) return (TaskFailureSignal) t
            for (Throwable s : t.suppressed) {
                if (s instanceof TaskFailureSignal) return (TaskFailureSignal) s
            }
            t = t.cause
        }
        return null
    }

    /** True when {@link #of} finds a signal that is permanent. */
    static boolean isPermanent(Throwable failure) {
        TaskFailureSignal signal = of(failure)
        return signal != null && signal.permanent
    }

    /**
     * Mark a failure. Called by TaskBase where it emits the FAILED event. A failure that already
     * carries a signal for this task is left alone, and marking never throws.
     */
    static void attach(Throwable failure, String taskId, String notRetriedBecause, int attempts) {
        if (failure == null) return
        try {
            for (Throwable s : failure.suppressed) {
                if (s instanceof TaskFailureSignal && ((TaskFailureSignal) s).taskId == taskId) return
            }
            failure.addSuppressed(new TaskFailureSignal(taskId, notRetriedBecause, attempts))
        } catch (RuntimeException ignored) {
            // an exception built with suppression off cannot carry it: the event still does
        }
    }
}
