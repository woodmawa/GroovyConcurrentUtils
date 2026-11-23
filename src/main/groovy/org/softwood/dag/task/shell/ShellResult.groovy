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
    /** 1.4.2: the processes left running and kept until the graph ends (keepBackground); empty otherwise. */
    List<Long> keptProcessIds = []
    /**
     * 1.4.2: what ends the kept processes. The runner sets it with keepBackground; the library hands
     * it to the graph, which closes it when the graph ends. Not part of {@link #toMap()}.
     */
    AutoCloseable keeper

    Map<String, Object> toMap() {
        return [exitCode       : exitCode,
                stdout         : stdout ?: '',
                stderr         : stderr ?: '',
                stdoutTruncated: stdoutTruncated,
                stderrTruncated: stderrTruncated,
                durationMs     : durationMs,
                keptProcessIds : new ArrayList<Long>(keptProcessIds ?: [])] as Map<String, Object>
    }
}
