package org.softwood.dag.task.shell

import groovy.transform.CompileStatic
import groovy.transform.Immutable

import java.time.Duration

/**
 * 1.4.1: one shell run, after {@link ShellGuard} has checked it. This is what a {@link ShellRunner}
 * receives: the interpreter is one the graph allows, the working directory is inside the graph's
 * directories, the environment is already scrubbed, and the limits are set.
 */
@CompileStatic
@Immutable(knownImmutableClasses = [File, Duration])
class ShellRequest {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60)
    static final int DEFAULT_MAX_STDOUT = 1024 * 1024
    static final int DEFAULT_MAX_STDERR = 64 * 1024

    String taskId
    /** The interpreter's id: powershell, cmd, bash or python. */
    String interpreter
    /** The executable to start. */
    String executable
    String script
    List<String> args
    File workingDirectory
    /** The whole environment the process gets. */
    Map<String, String> environment
    Duration timeout
    int maxStdout
    int maxStderr
    boolean trusted
}
