package org.softwood.dag.task.shell

import groovy.util.logging.Slf4j
import org.softwood.dag.task.FileTaskSecurityConfig
import org.softwood.dag.task.FileTaskSecurityValidator
import org.softwood.dag.task.TaskContext

import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern

/**
 * 1.4.1 (decision D2): the checks every shell run passes before any runner sees it, shared by
 * ShellTask and the {@code process} capability of a restricted script.
 *
 * <ol>
 *   <li>The graph has {@code allow 'process'}.</li>
 *   <li>The interpreter is known, and (unless the task is trusted) one the graph names with
 *       {@code interpreters}.</li>
 *   <li>The script asks for no elevation (sudo, runas, -Verb RunAs): refused trusted or not.</li>
 *   <li>Unless trusted, the script matches none of the interpreter's destructive-pattern
 *       tripwires.</li>
 *   <li>Arguments: no NUL. On Windows, no double quote or line break for any interpreter, and only
 *       plain characters for cmd and bash, because cmd and the WSL bash launcher read their command
 *       line again as a shell would.</li>
 *   <li>The working directory is the graph's, or one inside the graph's working directory and its
 *       {@code allowedDirectories}.</li>
 *   <li>The environment is rebuilt: the few variables an interpreter needs to start, the ones the
 *       graph names with {@code passEnvironment}, and the ones the task sets with {@code env}.</li>
 *   <li>A timeout (60 s by default) and output limits (1 MB stdout, 64 KB stderr by default), kept
 *       for trusted tasks too.</li>
 * </ol>
 *
 * <p>A refusal is a {@link SecurityException}; a configuration mistake an
 * {@link IllegalArgumentException}. Neither is retried.</p>
 */
@Slf4j
final class ShellGuard {

    private ShellGuard() {}

    static final ShellRunner DEFAULT_RUNNER = new LocalShellRunner()

    /** What an interpreter needs to start on Windows. Nothing that names the user or the machine. */
    static final List<String> KEEP_WINDOWS = ['SystemRoot', 'windir', 'SystemDrive', 'ComSpec', 'PATHEXT', 'PATH',
                                              'TEMP', 'TMP', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'LOCALAPPDATA',
                                              'APPDATA', 'ProgramData', 'ProgramFiles', 'PROCESSOR_ARCHITECTURE',
                                              'NUMBER_OF_PROCESSORS', 'OS'].asImmutable()

    /** What an interpreter needs to start elsewhere. */
    static final List<String> KEEP_UNIX = ['PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL'].asImmutable()

    private static final Pattern PLAIN_CMD = ~/^[A-Za-z0-9_.,:\/\\=+@-]*$/
    private static final Pattern PLAIN_BASH = ~/^[A-Za-z0-9_.,:\/=+@-]*$/
    private static final Pattern ENV_NAME = ~/^[A-Za-z_][A-Za-z0-9_]*$/

    /**
     * Check a run and build the request for it.
     *
     * @param checkDirectoryExists false when validating a graph that has not started: an upstream
     *        task may yet create the directory
     */
    static ShellRequest prepare(TaskContext ctx, String taskId, String interpreterName, String script, List args,
                                Object taskDirectory, Map env, Duration timeout, Integer maxStdout, Integer maxStderr,
                                boolean trusted, boolean checkDirectoryExists = true) {
        String who = "ShellTask '${taskId}'"
        if (ctx == null || !ctx.scriptCapabilities.contains('process')) {
            throw new SecurityException("${who}: running a shell needs allow 'process' on the graph")
        }
        Interpreter interpreter = Interpreter.of(interpreterName)
        if (interpreter == null) {
            throw new IllegalArgumentException("${who}: unknown interpreter '${interpreterName}'. Known: ${Interpreter.ids().join(', ')}")
        }
        if (!trusted) {
            Set<String> allowed = ctx.processInterpreters
            if (!allowed) {
                throw new SecurityException("${who}: the graph names no interpreters, so allow 'process' runs nothing: add, for example, interpreters '${interpreter.id}'")
            }
            if (!allowed.contains(interpreter.id)) {
                throw new SecurityException("${who}: interpreter '${interpreter.id}' is not one the graph allows (interpreters ${allowed.join(', ')})")
            }
        }
        if (!script?.trim()) {
            throw new IllegalArgumentException("${who}: a script is required")
        }
        String elevation = Interpreter.elevation(script)
        if (elevation) {
            throw new SecurityException("${who}: refused: the script asks for elevation (${elevation}); a shell task never runs elevated")
        }
        if (!trusted) {
            String wire = interpreter.tripwire(script)
            if (wire) {
                throw new SecurityException("${who}: refused: the script matches the destructive-pattern tripwire '${wire}' for ${interpreter.id}; if it is meant, the task needs trusted true")
            }
        }
        List<String> argList = checkedArgs(who, interpreter, args)
        File dir = workingDirectory(who, ctx, taskDirectory, checkDirectoryExists)
        Map<String, String> environment = environment(who, ctx, env)
        Duration limit = timeout ?: ShellRequest.DEFAULT_TIMEOUT
        if (limit.isZero() || limit.isNegative()) {
            throw new IllegalArgumentException("${who}: the timeout must be positive")
        }
        int out = maxStdout != null ? maxStdout : ShellRequest.DEFAULT_MAX_STDOUT
        int err = maxStderr != null ? maxStderr : ShellRequest.DEFAULT_MAX_STDERR
        if (out < 0 || err < 0) throw new IllegalArgumentException("${who}: output limits cannot be negative")
        String executable = ctx.interpreterPaths[interpreter.id] ?: interpreter.defaultExecutable()

        return new ShellRequest(taskId: taskId, interpreter: interpreter.id, executable: executable, script: script,
                args: argList, workingDirectory: dir, environment: environment, timeout: limit,
                maxStdout: out, maxStderr: err, trusted: trusted)
    }

    /** Run a checked request and leave an audit record, whether it succeeds or not. */
    static ShellResult execute(TaskContext ctx, ShellRequest request, ShellRunner runner) {
        ShellRunner chosen = runner ?: ctx?.shellRunner ?: DEFAULT_RUNNER
        if (request.trusted) {
            log.warn("ShellTask '{}': trusted true - the interpreter list and the tripwires are not applied", request.taskId)
        }
        long started = System.nanoTime()
        ShellResult result = null
        Throwable failure = null
        try {
            result = chosen.run(request)
            if (result == null) throw new IllegalStateException("ShellTask '${request.taskId}': the shell runner returned no result")
            return result
        } catch (Throwable t) {
            failure = t
            throw t
        } finally {
            ctx?.audit([kind           : 'shell',
                        taskId         : request.taskId,
                        interpreter    : request.interpreter,
                        workingDirectory: request.workingDirectory.path,
                        scriptSha256   : sha256(request.script),
                        argumentCount  : request.args.size(),
                        exitCode       : result?.exitCode,
                        durationMs     : result != null ? result.durationMs : (System.nanoTime() - started).intdiv(1_000_000L),
                        stdoutTruncated: result?.stdoutTruncated ?: false,
                        stderrTruncated: result?.stderrTruncated ?: false,
                        trusted        : request.trusted,
                        timedOut       : failure instanceof TimeoutException,
                        failure        : failure?.getClass()?.simpleName] as Map<String, Object>)
        }
    }

    static String sha256(String text) {
        MessageDigest.getInstance('SHA-256').digest((text ?: '').getBytes('UTF-8')).encodeHex().toString()
    }

    // ------------------------------------------------------------------ the parts

    private static List<String> checkedArgs(String who, Interpreter interpreter, List args) {
        List<String> out = []
        (args ?: []).eachWithIndex { Object a, int i ->
            if (a == null) throw new IllegalArgumentException("${who}: argument ${i + 1} is null")
            String s = a.toString()
            if (s.indexOf(0) >= 0) throw new SecurityException("${who}: refused argument ${i + 1}: it contains a NUL character")
            if (Interpreter.WINDOWS) {
                if (s.contains('"') || s.contains('\r') || s.contains('\n')) {
                    throw new SecurityException("${who}: refused argument ${i + 1}: a double quote or a line break cannot be passed safely on Windows")
                }
                if (interpreter == Interpreter.CMD && !PLAIN_CMD.matcher(s).matches()) {
                    throw new SecurityException("${who}: refused argument ${i + 1}: on Windows cmd reads its command line again, so its arguments are limited to letters, digits and _ . , : / \\ = + @ -")
                }
                if (interpreter == Interpreter.BASH && !PLAIN_BASH.matcher(s).matches()) {
                    throw new SecurityException("${who}: refused argument ${i + 1}: on Windows the bash launcher reads its command line again, so its arguments are limited to letters, digits and _ . , : / = + @ -")
                }
            }
            out << s
        }
        return out
    }

    private static File workingDirectory(String who, TaskContext ctx, Object taskDirectory, boolean checkExists) {
        File base = (ctx.workingDirectory ?: new File(System.getProperty('user.dir'))).canonicalFile
        File dir = base
        if (taskDirectory != null && taskDirectory.toString()) {
            File given = taskDirectory instanceof File ? (File) taskDirectory : new File(taskDirectory.toString())
            dir = (given.absolute ? given : new File(base, given.path)).canonicalFile
        }
        List<File> scope = [base]
        if (ctx.fileAllowedDirectories) scope.addAll(ctx.fileAllowedDirectories)
        FileTaskSecurityValidator validator = new FileTaskSecurityValidator(FileTaskSecurityConfig.strict(scope))
        if (!validator.validateFilePath(dir)) {
            throw new SecurityException("${who}: working directory ${dir} is outside the directories this graph may use")
        }
        if (checkExists && !dir.isDirectory()) {
            throw new IllegalArgumentException("${who}: working directory ${dir} does not exist")
        }
        return dir
    }

    private static Map<String, String> environment(String who, TaskContext ctx, Map taskEnv) {
        Map<String, String> env = new LinkedHashMap<String, String>()
        for (String name : (Interpreter.WINDOWS ? KEEP_WINDOWS : KEEP_UNIX)) {
            String value = System.getenv(name)
            if (value != null) env[name] = value
        }
        for (String name : ctx.passEnvironment) {
            String value = System.getenv(name)
            if (value != null) env[name] = value
        }
        taskEnv?.each { k, v ->
            String name = k?.toString()
            if (!name || !ENV_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("${who}: '${name}' is not an environment variable name")
            }
            env[name] = v == null ? '' : v.toString()
        }
        return env
    }
}
