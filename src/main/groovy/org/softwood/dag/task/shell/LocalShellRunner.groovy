package org.softwood.dag.task.shell

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 1.4.1 (decision D2): the default {@link ShellRunner}. Starts the interpreter as a child process.
 *
 * <ul>
 *   <li>The script goes to a temporary file in a directory of its own (PowerShell, cmd) or to
 *       stdin (bash, Python); stdin is closed once the script is written, so nothing can wait on a
 *       prompt.</li>
 *   <li>The process gets exactly the request's environment and working directory.</li>
 *   <li>stdout and stderr are read as they come and kept up to their limits; past a limit the rest is
 *       read and dropped, so the process never blocks on a full pipe, and the result is flagged.</li>
 *   <li>When the timeout passes, or the calling thread is interrupted, the process and every process
 *       it started are stopped.</li>
 * </ul>
 */
@Slf4j
@CompileStatic
class LocalShellRunner implements ShellRunner {

    @Override
    ShellResult run(ShellRequest request) {
        Interpreter interpreter = Interpreter.of(request.interpreter)
        if (interpreter == null) throw new IllegalArgumentException("unknown interpreter '${request.interpreter}'".toString())
        Path temp = Files.createTempDirectory('gcu-shell-')
        try {
            byte[] body = interpreter.scriptBytes(request.script)
            File scriptFile = null
            if (!interpreter.viaStdin) {
                scriptFile = temp.resolve('script' + interpreter.fileSuffix).toFile()
                scriptFile.bytes = body
            }
            List<String> command = interpreter.command(request.executable, scriptFile, request.args)
            ProcessBuilder builder = new ProcessBuilder(command).directory(request.workingDirectory)
            builder.environment().clear()
            builder.environment().putAll(request.environment)

            long started = System.nanoTime()
            Process process = builder.start()
            Capture out = new Capture(process.inputStream, request.maxStdout)
            Capture err = new Capture(process.errorStream, request.maxStderr)
            Thread outReader = Thread.ofVirtual().name('shell-out-' + request.taskId).start(out)
            Thread errReader = Thread.ofVirtual().name('shell-err-' + request.taskId).start(err)

            OutputStream stdin = process.outputStream
            try {
                if (interpreter.viaStdin) stdin.write(body)
            } catch (IOException ignored) {
                // the process ended before reading its script: its exit code says why
            } finally {
                try { stdin.close() } catch (IOException ignored) { }
            }

            boolean finished
            try {
                finished = process.waitFor(request.timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (InterruptedException e) {
                stopTree(process)
                Thread.currentThread().interrupt()
                throw e
            }
            if (!finished) {
                stopTree(process)
                throw new TimeoutException("${request.interpreter} ran longer than its timeout of ${request.timeout.toMillis()} ms and was stopped, with the processes it started".toString())
            }
            outReader.join(5000)
            errReader.join(5000)

            ShellResult result = new ShellResult()
            result.exitCode = process.exitValue()
            result.stdout = out.text()
            result.stderr = err.text()
            result.stdoutTruncated = out.truncated
            result.stderrTruncated = err.truncated
            result.durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            return result
        } finally {
            temp.toFile().deleteDir()
        }
    }

    /** Stop the process and everything it started: the descendants are listed first, while they still have a parent. */
    static void stopTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList()
        for (ProcessHandle h : descendants) {
            try { h.destroyForcibly() } catch (Exception ignored) { }
        }
        process.destroyForcibly()
        try {
            process.waitFor(5, TimeUnit.SECONDS)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    /** Reads a stream to its end, keeping at most {@code limit} bytes. */
    static final class Capture implements Runnable {
        private final InputStream input
        private final int limit
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream()
        volatile boolean truncated = false

        Capture(InputStream input, int limit) {
            this.input = input
            this.limit = Math.max(0, limit)
        }

        @Override
        void run() {
            byte[] buffer = new byte[8192]
            try {
                int n
                while ((n = input.read(buffer)) >= 0) {
                    int room = limit - kept.size()
                    if (room > 0) kept.write(buffer, 0, Math.min(room, n))
                    if (n > room) truncated = true
                }
            } catch (IOException ignored) {
                // the process was stopped
            } finally {
                try { input.close() } catch (IOException ignored) { }
            }
        }

        String text() {
            byte[] bytes = kept.toByteArray()
            int from = (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) ? 3 : 0
            def decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes, from, bytes.length - from)).toString()
        }
    }
}
