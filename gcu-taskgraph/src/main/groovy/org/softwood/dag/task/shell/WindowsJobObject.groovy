package org.softwood.dag.task.shell

import groovy.util.logging.Slf4j

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * 1.4.2: a Windows Job Object holding one shell run, so that stopping the run stops every process
 * it started, including those whose parent has already exited.
 *
 * <p>A walk of the process tree finds a process only through its parent. A process whose parent
 * exited is out of reach, and Git Bash (MSYS) starts the commands of a script that way, through a
 * fork stub that exits at once. A process started inside a job stays in the job whatever happens
 * to its parent, so ending the job ends them all.</p>
 *
 * <ul>
 *   <li>The job is made with kill-on-close: if this JVM dies during a run, Windows ends the run's
 *       processes when the job handle goes with it.</li>
 *   <li>{@link #terminate()} ends every process in the job. It is called however the run ends:
 *       on a timeout or a cancel, and when the script finishes, so a background process the script
 *       left running is stopped too (Will, 2026-11-23: nothing a run started outlives it). With
 *       {@code keepBackground true} on the task, a job that still holds processes when the script
 *       finishes is handed to the graph instead, and ended when the graph ends ({@link #close()}).</li>
 * </ul>
 *
 * <p>The calls go to kernel32 through the JDK's Foreign Function API. On a JDK that refuses
 * native access, or if any call fails, {@link #contain} returns null, logs once, and the caller
 * falls back to walking the tree. The JDK prints its own warning the first time native access is
 * used unless the JVM is started with {@code --enable-native-access=ALL-UNNAMED}.</p>
 *
 * <p>The process is put in the job just after it starts. A child it starts in that moment is
 * looked for and put in too; bash and Python have not been given their script yet, so they have
 * started nothing.</p>
 */
@Slf4j
final class WindowsJobObject implements AutoCloseable {

    private static final int PROCESS_TERMINATE = 0x0001
    private static final int PROCESS_SET_QUOTA = 0x0100
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private static final int JOB_OBJECT_BASIC_PROCESS_ID_LIST = 3
    /** The most process ids read back from a job. */
    private static final int MAX_LISTED = 512
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    /** sizeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION) on 64-bit Windows. */
    private static final long EXTENDED_LIMIT_SIZE = 144
    /** offsetof(..., BasicLimitInformation.LimitFlags). */
    private static final long LIMIT_FLAGS_OFFSET = 16

    private static final Object LOCK = new Object()
    private static volatile Kernel32 kernel32
    private static volatile boolean unavailable = false

    private final MemorySegment job
    private boolean ended = false

    private WindowsJobObject(MemorySegment job) {
        this.job = job
    }

    /** Put the process (and any child it has already started) in a new job; null if that cannot be done here. */
    static WindowsJobObject contain(Process process) {
        Kernel32 k = kernel32()
        if (k == null) return null
        MemorySegment job = null
        try {
            job = (MemorySegment) k.createJob.invokeWithArguments(MemorySegment.NULL, MemorySegment.NULL)
            if (isNull(job)) return refused('CreateJobObjectW', null)
            if (!setKillOnClose(k, job, true)) return refused('SetInformationJobObject', job)
            if (!assign(k, job, process.pid())) return refused('AssignProcessToJobObject', job)
            for (ProcessHandle early : process.descendants().toList()) assign(k, job, early.pid())
            return new WindowsJobObject(job)
        } catch (Throwable t) {
            log.warn("WindowsJobObject: could not contain the shell run ({}); its processes are stopped by walking the tree", t.toString())
            closeQuietly(k, job)
            return null
        }
    }

    /** The ids of the processes in the job now; empty once it has ended or if it cannot be read. */
    synchronized List<Long> processIds() {
        if (ended) return []
        Kernel32 k = kernel32
        Arena arena = Arena.ofConfined()
        try {
            long size = 8L + 8L * MAX_LISTED
            MemorySegment info = arena.allocate(size, 8)
            int ok = (Integer) k.queryInformation.invokeWithArguments(job, JOB_OBJECT_BASIC_PROCESS_ID_LIST, info, (int) size, MemorySegment.NULL)
            if (ok == 0) return []
            int listed = info.get(ValueLayout.JAVA_INT, 4L)
            List<Long> ids = []
            for (int i = 0; i < listed; i++) ids << info.get(ValueLayout.JAVA_LONG, 8L + 8L * i)
            return ids
        } catch (Throwable t) {
            log.warn("WindowsJobObject: QueryInformationJobObject failed ({})", t.toString())
            return []
        } finally {
            arena.close()
        }
    }

    /** Ends the job: the same as {@link #terminate()}. */
    @Override
    void close() {
        terminate()
    }

    /** End every process in the job. */
    synchronized void terminate() {
        if (ended) return
        Kernel32 k = kernel32
        try {
            k.terminateJob.invokeWithArguments(job, 1)
        } catch (Throwable t) {
            log.warn("WindowsJobObject: TerminateJobObject failed ({})", t.toString())
        }
        ended = true
        closeQuietly(k, job)
    }

    // ------------------------------------------------------------------ kernel32

    private static final class Kernel32 {
        MethodHandle createJob, setInformation, queryInformation, openProcess, assign, terminateJob, closeHandle
    }

    private static Kernel32 kernel32() {
        if (unavailable) return null
        Kernel32 k = kernel32
        if (k != null) return k
        synchronized (LOCK) {
            if (unavailable) return null
            if (kernel32 != null) return kernel32
            try {
                Linker linker = Linker.nativeLinker()
                SymbolLookup lib = SymbolLookup.libraryLookup('kernel32', Arena.global())
                Closure<MethodHandle> bind = { String name, FunctionDescriptor fd ->
                    linker.downcallHandle(lib.find(name).orElseThrow { new IllegalStateException("kernel32 has no ${name}") }, fd)
                }
                def ptr = ValueLayout.ADDRESS
                def i32 = ValueLayout.JAVA_INT
                Kernel32 made = new Kernel32()
                made.createJob = bind('CreateJobObjectW', FunctionDescriptor.of(ptr, ptr, ptr))
                made.setInformation = bind('SetInformationJobObject', FunctionDescriptor.of(i32, ptr, i32, ptr, i32))
                made.queryInformation = bind('QueryInformationJobObject', FunctionDescriptor.of(i32, ptr, i32, ptr, i32, ptr))
                made.openProcess = bind('OpenProcess', FunctionDescriptor.of(ptr, i32, i32, i32))
                made.assign = bind('AssignProcessToJobObject', FunctionDescriptor.of(i32, ptr, ptr))
                made.terminateJob = bind('TerminateJobObject', FunctionDescriptor.of(i32, ptr, i32))
                made.closeHandle = bind('CloseHandle', FunctionDescriptor.of(i32, ptr))
                kernel32 = made
                return made
            } catch (Throwable t) {
                unavailable = true
                log.warn("WindowsJobObject: Job Objects are not available ({}); shell runs are stopped by walking the process tree", t.toString())
                return null
            }
        }
    }

    private static boolean setKillOnClose(Kernel32 k, MemorySegment job, boolean on) {
        Arena arena = Arena.ofConfined()
        try {
            MemorySegment info = arena.allocate(EXTENDED_LIMIT_SIZE, 8)
            info.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, on ? JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE : 0)
            return ((Integer) k.setInformation.invokeWithArguments(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, (int) EXTENDED_LIMIT_SIZE)) != 0
        } finally {
            arena.close()
        }
    }

    private static boolean assign(Kernel32 k, MemorySegment job, long pid) {
        MemorySegment process = (MemorySegment) k.openProcess.invokeWithArguments(PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, (int) pid)
        if (isNull(process)) return false
        try {
            return ((Integer) k.assign.invokeWithArguments(job, process)) != 0
        } finally {
            k.closeHandle.invokeWithArguments(process)
        }
    }

    private static WindowsJobObject refused(String call, MemorySegment job) {
        log.warn("WindowsJobObject: {} failed; this run's processes are stopped by walking the tree", call)
        closeQuietly(kernel32, job)
        return null
    }

    private static void closeQuietly(Kernel32 k, MemorySegment handle) {
        if (k == null || isNull(handle)) return
        try {
            k.closeHandle.invokeWithArguments(handle)
        } catch (Throwable ignored) {
        }
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.address() == 0L
    }
}
