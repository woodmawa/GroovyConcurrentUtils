package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.dag.task.shell.ShellGuard
import org.softwood.dag.task.shell.ShellRequest
import org.softwood.dag.task.shell.ShellResult
import org.softwood.dag.task.shell.ShellRunner
import org.softwood.promise.Promise

import java.time.Duration

/**
 * ShellTask (1.4.1, decision D2): runs a PowerShell, cmd, bash or Python script as an operating
 * system process.
 *
 * <h3>Off by default</h3>
 * <p>A graph grants shell execution with {@code allow 'process'} and names the interpreters its
 * tasks may use with {@code interpreters}. Without both, the graph does not start.</p>
 *
 * <h3>The controls ({@link ShellGuard})</h3>
 * <ul>
 *   <li>The script goes to the interpreter by a temporary file or stdin, never on the command line.
 *       Arguments are passed as a list and are not parsed by a shell (on Windows, cmd and bash take
 *       plain characters only).</li>
 *   <li>The working directory is the graph's, or one inside its directories.</li>
 *   <li>The environment is rebuilt from a short keep-list, {@code passEnvironment} on the graph and
 *       {@code env} on the task.</li>
 *   <li>{@code processTimeout} (60 s by default) stops the process and every process it started.
 *       Output is capped ({@code maxOutput} 1 MB, {@code maxErrorOutput} 64 KB) and flagged when cut.</li>
 *   <li>Destructive patterns are refused before anything runs; so is elevation.</li>
 *   <li>Each run leaves an audit record (see {@link TaskContext#audit}).</li>
 * </ul>
 *
 * <h3>Result</h3>
 * <p>A map: {@code exitCode}, {@code stdout}, {@code stderr}, {@code stdoutTruncated},
 * {@code stderrTruncated}, {@code durationMs}, {@code keptProcessIds}. A non-zero exit fails the task
 * as an ordinary failure, so its retry policy applies. A refusal is never retried.</p>
 *
 * <h3>Background processes (1.4.2)</h3>
 * <p>On Windows nothing a script starts outlives the task: what it leaves running is stopped when it
 * finishes. {@code keepBackground true} keeps those processes instead, until the graph ends
 * (completed, failed, aborted or timed out), so a later task can use them; their ids are in
 * {@code keptProcessIds}. A timeout or a failed script still stops them.</p>
 *
 * <h3>trusted true</h3>
 * <p>Skips the interpreter list and the tripwires. {@code allow 'process'}, the working directory,
 * the environment, elevation, the timeout and the output caps still apply. Logged each run.</p>
 *
 * <h3>Example</h3>
 * <pre>
 * TaskGraph.build {
 *     allow 'process'
 *     interpreters 'powershell'
 *     workingDirectory '/work/run-42'
 *     shellTask('list') {
 *         interpreter 'powershell'
 *         script 'Get-ChildItem | Select-Object -ExpandProperty Name'
 *         processTimeout Duration.ofSeconds(20)
 *     }
 * }
 * </pre>
 */
@Slf4j
class ShellTask extends TaskBase<Object> implements SelfValidating {

    String interpreterName
    String script
    List<Object> argList = []
    Closure argsProvider
    Object taskWorkingDirectory
    Map<String, Object> envVars = [:]
    Duration processTimeout = ShellRequest.DEFAULT_TIMEOUT
    int maxOutput = ShellRequest.DEFAULT_MAX_STDOUT
    int maxErrorOutput = ShellRequest.DEFAULT_MAX_STDERR
    ShellRunner runner
    /** 1.4.2: keep the background processes the script leaves running until the graph ends. */
    boolean keepBackgroundProcesses = false

    private boolean trustedTask = false

    ShellTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // ------------------------------------------------------------------ DSL

    /** powershell, cmd, bash or python. */
    void interpreter(String name) { this.interpreterName = name }

    void script(String code) { this.script = code }

    /** Arguments passed to the script as a list. */
    void args(Object... values) { this.argList = (values ?: []) as List<Object> }

    /** Arguments computed when the task runs: the closure takes (ctx, prev) and returns a list. */
    void args(Closure provider) { this.argsProvider = provider }

    /** The process's working directory: relative to the graph's, and inside the graph's directories. */
    void workingDirectory(Object dir) { this.taskWorkingDirectory = dir }

    /** Set an environment variable for the process. */
    void env(String name, Object value) { this.envVars[name] = value }

    void processTimeout(Duration timeout) { this.processTimeout = timeout }

    /** The most stdout kept, in bytes. */
    void maxOutput(int bytes) { this.maxOutput = bytes }

    /** The most stderr kept, in bytes. */
    void maxErrorOutput(int bytes) { this.maxErrorOutput = bytes }

    /** A runner of the flow's own for this task, in place of the graph's or the local one. */
    void runner(ShellRunner value) { this.runner = value }

    /**
     * 1.4.2: the script means to leave processes running (a server, a watcher): keep them until the
     * graph ends rather than stopping them when the script finishes.
     */
    void keepBackground(boolean value) { this.keepBackgroundProcesses = value }

    /** Skip the interpreter list and the tripwires. Logged each run. */
    void trusted(boolean value) { this.trustedTask = value }

    boolean isTrusted() { trustedTask }

    // ------------------------------------------------------------------ validation

    @Override
    List<String> validationProblems() {
        try {
            ShellGuard.prepare(ctx, id, interpreterName, script, argsProvider ? [] : argList, taskWorkingDirectory,
                    envVars, processTimeout, maxOutput, maxErrorOutput, trustedTask, false)
            return []
        } catch (Exception e) {
            return [e.message ?: e.toString()]
        }
    }

    /** A refusal or a configuration mistake is the same on every attempt. */
    @Override
    protected boolean isRetriable(Throwable err) {
        for (Throwable t = err; t != null; t = t.cause) {
            if (t instanceof SecurityException || t instanceof IllegalArgumentException) return false
            if (t.cause === t) break
        }
        return super.isRetriable(err)
    }

    // ------------------------------------------------------------------ running

    @Override
    protected Promise<Object> runTask(TaskContext ctx, Object prevValue) {
        return ctx.promiseFactory.executeAsync {
            List args = argsProvider ? (argsProvider.call(ctx, prevValue) as List) : argList
            ShellRequest request = ShellGuard.prepare(ctx, id, interpreterName, script, args, taskWorkingDirectory,
                    envVars, processTimeout, maxOutput, maxErrorOutput, trustedTask, true, keepBackgroundProcesses)
            ShellResult result = ShellGuard.execute(ctx, request, runner)
            if (result.exitCode != 0) {
                // a script that failed has not set up what it meant to keep
                if (result.keeper != null) {
                    try { result.keeper.close() } catch (Exception ignored) { }
                }
                String detail = result.stderr?.trim()
                if (detail && detail.length() > 400) detail = detail.substring(0, 397) + '...'
                throw new ShellExecutionException("ShellTask '${id}': ${request.interpreter} finished with exit code ${result.exitCode}" +
                        (detail ? ": ${detail}" : ''), result)
            }
            return result.toMap()
        }
    }

    /** A script that ran and finished with a non-zero exit code. Retried by the task's retry policy. */
    static class ShellExecutionException extends RuntimeException {
        final Map<String, Object> result

        ShellExecutionException(String message, ShellResult result) {
            super(message)
            this.result = result?.toMap()
        }
    }
}
