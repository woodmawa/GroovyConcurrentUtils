package org.softwood.dag.script

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.shell.ShellGuard
import org.softwood.dag.task.shell.ShellRequest
import org.softwood.dag.task.shell.ShellResult

/**
 * 1.4.1 (decision D2, level 2): what {@code allow 'process'} gives a restricted script, bound as
 * {@code process}.
 *
 * <p>{@code process.shell(interpreter, script, args)} runs a script through the graph's shell runner
 * with exactly the checks a ShellTask has ({@link ShellGuard}): an interpreter the graph names, no
 * tripwire, no elevation, the graph's working directory, a scrubbed environment, 60 s and the
 * output limits. It returns the same map as a ShellTask, and does not fail on a non-zero exit: the
 * script reads {@code exitCode}. A refusal is a {@link SecurityException} and is not retried.</p>
 *
 * <p>A restricted script is never trusted, so a script cannot reach the interpreter list or the
 * tripwires from here.</p>
 */
class ScriptProcess implements ScriptCapability {

    /** Not 'run': a restricted script may not call run(), which would run the script itself again. */
    private static final Set<String> OFFERED = Collections.unmodifiableSet(new LinkedHashSet<String>(['shell']))

    private final TaskContext ctx
    private final String taskId

    ScriptProcess(TaskContext ctx, String taskId) {
        this.ctx = ctx
        this.taskId = taskId
    }

    @Override
    String capabilityName() { 'process' }

    @Override
    Set<String> offeredMethods() { OFFERED }

    Map<String, Object> shell(String interpreter, String script, List args = []) {
        ShellRequest request = ShellGuard.prepare(ctx, taskId, interpreter, script, args, null, [:],
                ShellRequest.DEFAULT_TIMEOUT, ShellRequest.DEFAULT_MAX_STDOUT, ShellRequest.DEFAULT_MAX_STDERR, false, true)
        ShellResult result = ShellGuard.execute(ctx, request, null)
        return result.toMap()
    }
}
