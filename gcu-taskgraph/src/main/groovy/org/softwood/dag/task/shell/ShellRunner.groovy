package org.softwood.dag.task.shell

import groovy.transform.CompileStatic

/**
 * 1.4.1 (decision D2): runs a checked shell request.
 *
 * <p>The default is {@link LocalShellRunner}, which starts the interpreter as a child process of
 * this JVM. A flow can set its own with {@code shellRunner} on the graph or {@code runner} on the
 * task: AW plugs in one that hands the command to FS {@code execute}, so the platform keeps one
 * guard. GCU's own checks ({@link ShellGuard}) run before any runner is called.</p>
 *
 * <p>A runner must honour the request's timeout and output limits. It throws
 * {@link java.util.concurrent.TimeoutException} when the time is up, and must stop the process
 * tree when it does or when the calling thread is interrupted.</p>
 */
@CompileStatic
interface ShellRunner {

    ShellResult run(ShellRequest request)
}
