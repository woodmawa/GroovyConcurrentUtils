package org.softwood.dag.task.shell

import groovy.transform.CompileStatic

/**
 * 1.4.1: what a {@link ShellRunner} returns. A ShellTask's result is {@link #toMap()}.
 */
@CompileStatic
class ShellResult {

    int exitCode
    String stdout = ''
    String stderr = ''
    boolean stdoutTruncated
    boolean stderrTruncated
    long durationMs

    Map<String, Object> toMap() {
        return [exitCode       : exitCode,
                stdout         : stdout ?: '',
                stderr         : stderr ?: '',
                stdoutTruncated: stdoutTruncated,
                stderrTruncated: stderrTruncated,
                durationMs     : durationMs] as Map<String, Object>
    }
}
