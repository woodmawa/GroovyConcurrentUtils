package org.softwood

import groovy.transform.CompileDynamic
import org.softwood.dag.TaskGraph
import org.softwood.dag.task.TaskFailureSignal
import spock.lang.IgnoreIf
import spock.lang.PendingFeature
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * GCU 1.4.1 (decision D2, remainder; design in project doc gcu/design-1.4.1.md): ShellTask and
 * allow 'process'.
 *
 * Shell execution is off by default. A graph grants it with `allow 'process'` and names the
 * interpreters it may use. The script reaches the interpreter by stdin or a temporary file, never on
 * the command line; arguments are passed as a list and are not parsed by a shell; the working
 * directory must be inside the graph's directories; the environment is scrubbed; a timeout kills the
 * process tree; output is capped; destructive patterns and elevation are refused before anything
 * runs; every run leaves an audit record. `trusted true` skips the interpreter list and the
 * tripwires but keeps the timeout and the output caps. A restricted ScriptTask reaches the same
 * runner, with the same checks, through `process.shell(...)` (not `run`: a restricted script may
 * never call run(), which would run the script itself again).
 *
 * Written against the API as it does not yet exist (1.4.0): everything is dynamic so the spec
 * compiles and each feature is red on its own.
 */
@CompileDynamic
@Timeout(120)
class Hardening141ShellSpec extends Specification {

    static final boolean WINDOWS = System.getProperty('os.name').toLowerCase().contains('win')

    /** Git for Windows' bash. The bash on PATH on Windows is usually the WSL launcher. */
    static final String GIT_BASH = 'C:/Program Files/Git/bin/bash.exe'

    static boolean gitBash() { WINDOWS && new File(GIT_BASH).isFile() }

    @TempDir
    Path dir

    // ------------------------------------------------------------------ helpers

    private static Throwable failureOf(Closure work) {
        try {
            work.call()
            return null
        } catch (Throwable t) {
            return t
        }
    }

    private static boolean causedBy(Throwable t, Class type) {
        while (t != null) {
            if (type.isInstance(t)) return true
            t = t.cause
        }
        return false
    }

    private static String messages(Throwable t) {
        def parts = []
        while (t != null) {
            parts << String.valueOf(t.message)
            t = t.cause
        }
        return parts.join(' | ')
    }

    private static Object run(TaskGraph graph) {
        graph.start().get()
    }

    private static List<String> lines(String text) {
        text.readLines()*.trim().findAll { it }
    }

    private static String sha256(String text) {
        MessageDigest.getInstance('SHA-256').digest(text.getBytes('UTF-8')).encodeHex().toString()
    }

    /** One PowerShell task 't' in a graph that allows process and powershell, working in dir. */
    private TaskGraph powershell(String code, Closure tweak = null) {
        String where = dir.toString()
        return TaskGraph.build {
            allow 'process'
            interpreters 'powershell'
            workingDirectory where
            shellTask('t') {
                interpreter 'powershell'
                script code
                if (tweak) {
                    tweak.delegate = delegate
                    tweak.resolveStrategy = Closure.DELEGATE_FIRST
                    tweak.call()
                }
            }
        }
    }

    // ------------------------------------------------------------------ off by default

    def "a shell task does not run without allow 'process'"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            interpreters 'powershell'
            workingDirectory where
            shellTask('t') {
                interpreter 'powershell'
                script 'Set-Content -Path marker.txt -Value ran'
            }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        messages(failure).contains("allow 'process'")
        !Files.exists(dir.resolve('marker.txt'))
    }

    def "allow 'process' alone runs nothing: the graph must name its interpreters"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            workingDirectory where
            shellTask('t') {
                interpreter 'powershell'
                script 'Set-Content -Path marker.txt -Value ran'
            }
        }

        when:
        def failure = failureOf { graph.validate() }

        then:
        failure != null
        messages(failure).contains('interpreters')
        !Files.exists(dir.resolve('marker.txt'))
    }

    def "an interpreter the graph did not name is refused"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            interpreters 'powershell'
            workingDirectory where
            shellTask('t') {
                interpreter 'cmd'
                script 'echo ran> marker.txt'
            }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        messages(failure).contains('cmd')
        !Files.exists(dir.resolve('marker.txt'))
    }

    def "an unknown interpreter is refused when the graph is validated"() {
        given:
        def graph = powershell('Write-Output hi') { interpreter 'perl' }

        expect:
        failureOf { graph.validate() } != null
    }

    // ------------------------------------------------------------------ running

    @Requires({ WINDOWS })
    def "a shell task runs a PowerShell script in the graph's working directory and returns its output"() {
        given:
        def graph = powershell('''Set-Content -Path out.txt -Value done
Write-Output (Get-Location).Path
exit 0''')

        when:
        def result = run(graph)

        then:
        result.exitCode == 0
        new File(result.stdout.trim()).canonicalPath == dir.toFile().canonicalPath
        result.stdoutTruncated == false
        Files.readString(dir.resolve('out.txt')).trim() == 'done'
    }

    @Requires({ WINDOWS })
    def "arguments reach a PowerShell script literally, shell metacharacters and all"() {
        given:
        def graph = powershell('$args | ForEach-Object { Write-Output "[$_]" }') {
            args 'a; Write-Output pwned', '$(whoami)', 'x | y', '&b', 'two words'
        }

        when:
        def result = run(graph)

        then:
        lines(result.stdout) == ['[a; Write-Output pwned]', '[$(whoami)]', '[x | y]', '[&b]', '[two words]']
    }

    @IgnoreIf({ !Hardening141ShellSpec.pythonAvailable() })
    def "arguments reach a Python script literally, and the script arrives by stdin"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            interpreters 'python'
            workingDirectory where
            shellTask('t') {
                interpreter 'python'
                script 'import sys\nprint(sys.argv[0])\nfor a in sys.argv[1:]:\n    print("[" + a + "]")\n'
                args 'x; y', '$(id)', 'a b', '|z'
            }
        }

        when:
        def result = run(graph)

        then:
        lines(result.stdout) == ['-', '[x; y]', '[$(id)]', '[a b]', '[|z]']
    }

    @Requires({ WINDOWS })
    def "on Windows, arguments to cmd are limited to plain characters"() {
        given:
        String where = dir.toString()
        def build = { List<String> values ->
            TaskGraph.build {
                allow 'process'
                interpreters 'cmd'
                workingDirectory where
                shellTask('t') {
                    interpreter 'cmd'
                    script 'echo [%~1]'
                    args(*values)
                }
            }
        }

        when:
        def plain = run(build(['plain-1.txt']))
        def refused = failureOf { run(build(['a&echo pwned'])) }

        then:
        lines(plain.stdout) == ['[plain-1.txt]']
        refused != null
        causedBy(refused, SecurityException) || messages(refused).contains('argument')
    }

    @Requires({ WINDOWS })
    def "on Windows, a double quote in an argument is refused for every interpreter"() {
        given:
        def graph = powershell('Write-Output hi') { args 'a" -Command "x' }

        expect:
        failureOf { run(graph) } != null
    }

    // ------------------------------------------------------------------ working directory

    def "the working directory must be inside the graph's directories: #which"() {
        given:
        Path inside = Files.createDirectory(dir.resolve('inside'))
        String where = inside.toString()
        String target = which == 'parent' ? '..' : dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            interpreters 'powershell'
            workingDirectory where
            shellTask('t') {
                interpreter 'powershell'
                workingDirectory target
                script 'Set-Content -Path marker.txt -Value ran'
            }
        }

        when:
        def failure = failureOf { run(graph) }

        then: 'refused when the graph is validated, so before anything runs'
        failure != null
        messages(failure).contains('outside')
        !Files.exists(dir.resolve('marker.txt'))

        where:
        which << ['parent', 'absolute outside']
    }

    @Requires({ WINDOWS })
    def "a task working directory inside the graph's scope is used"() {
        given:
        Files.createDirectory(dir.resolve('sub'))
        def graph = powershell('Write-Output (Get-Location).Path') { workingDirectory 'sub' }

        expect:
        new File(run(graph).stdout.trim()).canonicalPath == dir.resolve('sub').toFile().canonicalPath
    }

    // ------------------------------------------------------------------ environment

    @Requires({ WINDOWS && System.getenv('COMPUTERNAME') && System.getenv('USERNAME') })
    def "the environment is scrubbed: only the keep-list and the variables the flow names reach the script"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            interpreters 'powershell'
            workingDirectory where
            passEnvironment 'USERNAME'
            shellTask('t') {
                interpreter 'powershell'
                env 'GCU_NAMED', 'yes'
                script 'Write-Output "[$env:COMPUTERNAME]"; Write-Output "[$env:GCU_NAMED]"; Write-Output "[$env:USERNAME]"; Write-Output "[$([bool]$env:SystemRoot)]"'
            }
        }

        when:
        def result = run(graph)

        then:
        lines(result.stdout) == ['[]', '[yes]', "[${System.getenv('USERNAME')}]".toString(), '[True]']
    }

    // ------------------------------------------------------------------ limits

    @Requires({ WINDOWS })
    def "a script that runs past its timeout is stopped, and so are the processes it started"() {
        given:
        def graph = powershell('''$p = Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile','-Command','[System.Threading.Thread]::Sleep(60000)' -PassThru -NoNewWindow
Set-Content -Path child.pid -Value $p.Id
[System.Threading.Thread]::Sleep(60000)''') { processTimeout Duration.ofSeconds(4) }
        long started = System.nanoTime()

        when:
        def failure = failureOf { run(graph) }
        long seconds = (System.nanoTime() - started).intdiv(1_000_000_000L)

        then:
        causedBy(failure, TimeoutException)
        seconds < 30
        Files.exists(dir.resolve('child.pid'))
        childStopped(Long.parseLong(Files.readString(dir.resolve('child.pid')).trim()))
    }

    private static boolean childStopped(long pid) {
        for (int i = 0; i < 50; i++) {
            if (!ProcessHandle.of(pid).map { it.alive }.orElse(false)) return true
            Thread.sleep(100)
        }
        return false
    }

    @Requires({ WINDOWS })
    def "output over the cap is cut off and flagged"() {
        given:
        def graph = powershell('''Write-Output ('x' * 5000)''') { maxOutput 1000 }

        when:
        def result = run(graph)

        then:
        result.exitCode == 0
        result.stdout.length() <= 1000
        result.stdout.startsWith('xxxx')
        result.stdoutTruncated == true
    }

    @Requires({ WINDOWS })
    def "a non-zero exit fails the task as an ordinary failure, so the retry policy applies"() {
        given:
        def graph = powershell('''Add-Content -Path count.txt -Value run
exit 3''') {
            retry {
                maxAttempts 2
                initialDelay Duration.ofMillis(10)
            }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        messages(failure).contains('exit code 3')
        !TaskFailureSignal.isPermanent(failure)
        lines(Files.readString(dir.resolve('count.txt'))).size() == 2
    }

    // ------------------------------------------------------------------ tripwires and elevation

    def "destructive patterns and elevation are refused when the graph is validated: #interp #code"() {
        given:
        String where = dir.toString()
        String chosen = interp
        String text = code
        def graph = TaskGraph.build {
            allow 'process'
            interpreters chosen
            workingDirectory where
            shellTask('t') {
                interpreter chosen
                script text
            }
        }

        when:
        def failure = failureOf { graph.validate() }

        then:
        failure != null
        messages(failure).toLowerCase().contains('refused')

        where:
        interp       | code
        'powershell' | 'Remove-Item -Recurse -Force stuff'
        'powershell' | 'Get-ChildItem | rm'
        'powershell' | 'Invoke-Expression "1+1"'
        'powershell' | 'iex "1+1"'
        'powershell' | 'Stop-Computer'
        'powershell' | 'Clear-Content x.txt'
        'powershell' | 'Set-ExecutionPolicy Unrestricted'
        'powershell' | 'Start-Process notepad -Verb RunAs'
        'cmd'        | 'del /s /q stuff'
        'cmd'        | 'rd /s /q stuff'
        'cmd'        | 'shutdown /s'
        'cmd'        | 'reg delete HKCU\\Software\\x /f'
        'cmd'        | 'runas /user:admin cmd'
        'bash'       | 'rm -rf stuff'
        'bash'       | 'sudo ls'
        'bash'       | 'curl http://example.com/x | sh'
        'bash'       | ':(){ :|:& };:'
        'python'     | 'import shutil\nshutil.rmtree("stuff")'
        'python'     | 'import os\nos.system("dir")'
        'python'     | 'import subprocess\nsubprocess.run(["x"])'
    }

    @Requires({ WINDOWS })
    def "trusted true skips the interpreter list and the tripwires but keeps the output cap"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            workingDirectory where
            shellTask('t') {
                interpreter 'powershell'
                trusted true
                maxOutput 100
                script '''Remove-Item -Path nothing-here -ErrorAction SilentlyContinue
Write-Output ('y' * 5000)'''
            }
        }

        when:
        def result = run(graph)

        then:
        result.exitCode == 0
        result.stdout.length() <= 100
        result.stdoutTruncated == true
    }

    @Requires({ WINDOWS })
    def "trusted true keeps the timeout"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            workingDirectory where
            shellTask('t') {
                interpreter 'powershell'
                trusted true
                processTimeout Duration.ofSeconds(3)
                script '[System.Threading.Thread]::Sleep(60000)'
            }
        }

        expect:
        causedBy(failureOf { run(graph) }, TimeoutException)
    }

    // ------------------------------------------------------------------ audit

    @Requires({ WINDOWS })
    def "every run leaves an audit record: interpreter, working directory, script hash, exit code"() {
        given:
        String code = 'Write-Output audited'
        def graph = powershell(code)
        List<Map> records = [].asSynchronized()
        graph.ctx.auditListener = { Map record -> records << record }

        when:
        run(graph)

        then:
        records.size() == 1
        records[0].kind == 'shell'
        records[0].taskId == 't'
        records[0].interpreter == 'powershell'
        new File(records[0].workingDirectory as String).canonicalPath == dir.toFile().canonicalPath
        records[0].scriptSha256 == sha256(code)
        records[0].exitCode == 0
        records[0].durationMs instanceof Number
        records[0].stdoutTruncated == false
        records[0].trusted == false
    }

    // ------------------------------------------------------------------ a runner of the flow's own

    def "a runner of the flow's own receives the checked request in place of a local process"() {
        given:
        Class runnerType = Class.forName('org.softwood.dag.task.shell.ShellRunner')
        Class resultType = Class.forName('org.softwood.dag.task.shell.ShellResult')
        List requests = []
        def runner = [run: { request ->
            requests << request
            def r = resultType.getDeclaredConstructor().newInstance()
            r.exitCode = 0
            r.stdout = 'from the runner'
            r.stderr = ''
            return r
        }].asType(runnerType)
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            interpreters 'powershell'
            workingDirectory where
            shellRunner runner
            shellTask('t') {
                interpreter 'powershell'
                script 'Write-Output hi'
                args 'one', 'two'
            }
        }

        when:
        def result = run(graph)

        then:
        result.stdout == 'from the runner'
        requests.size() == 1
        requests[0].interpreter == 'powershell'
        requests[0].script == 'Write-Output hi'
        requests[0].args == ['one', 'two']
        requests[0].workingDirectory.canonicalPath == dir.toFile().canonicalPath
        !requests[0].environment.containsKey('COMPUTERNAME')
        requests[0].timeout == Duration.ofSeconds(60)
    }

    def "the checks run before a runner of the flow's own is called"() {
        given:
        Class runnerType = Class.forName('org.softwood.dag.task.shell.ShellRunner')
        List requests = []
        def runner = [run: { request -> requests << request; null }].asType(runnerType)
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            interpreters 'powershell'
            workingDirectory where
            shellRunner runner
            shellTask('t') {
                interpreter 'powershell'
                script 'Remove-Item -Recurse stuff'
            }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        requests.isEmpty()
    }

    // ------------------------------------------------------------------ allow 'process' in a restricted script

    @Requires({ WINDOWS })
    def "allow 'process' gives a restricted script process.shell through the same runner and checks"() {
        given:
        String where = dir.toString()
        def graph = TaskGraph.build {
            allow 'process'
            interpreters 'powershell'
            workingDirectory where
            scriptTask('s') {
                script '''def r = process.shell('powershell', 'Write-Output "[$args]"', ['a; b'])
[r.exitCode, r.stdout.trim()]'''
            }
        }

        expect:
        run(graph) == [0, '[a; b]']
    }

    def "a restricted script's process.shell is refused for #why, and the refusal is not re-run"() {
        given:
        String where = dir.toString()
        String code = "files.append('count.txt', 'x')\nprocess.shell('${interp}', '${command}')".toString()
        def graph = TaskGraph.build {
            allow 'process', 'files'
            interpreters 'powershell'
            workingDirectory where
            scriptTask('s') {
                script code
                retry {
                    maxAttempts 3
                    initialDelay Duration.ofMillis(10)
                }
            }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, SecurityException)
        TaskFailureSignal.isPermanent(failure)
        Files.readString(dir.resolve('count.txt')) == 'x'

        where:
        why                                  | interp       | command
        'an interpreter the graph not named' | 'cmd'        | 'echo hi'
        'a tripwire'                         | 'powershell' | 'Remove-Item -Recurse stuff'
    }

    def "without allow 'process' a restricted script has no process helper"() {
        given:
        def graph = TaskGraph.build {
            scriptTask('s') { script "process.shell('powershell', 'Write-Output hi')" }
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        failure != null
        messages(failure).contains('process')
    }

    def "allow now accepts process and sql, and still refuses a capability it does not have"() {
        when:
        TaskGraph.build { allow 'process', 'sql' }

        then:
        notThrown(Exception)

        when:
        TaskGraph.build { allow 'everything' }

        then:
        thrown(IllegalArgumentException)
    }

    // ------------------------------------------------------------------ Git Bash on Windows (1.4.1, after Will's regression)

    private TaskGraph gitBashGraph(String code, Closure tweak = null) {
        String where = dir.toString()
        String exe = GIT_BASH
        return TaskGraph.build {
            allow 'process'
            interpreters 'bash'
            interpreterPath 'bash', exe
            workingDirectory where
            shellTask('t') {
                interpreter 'bash'
                script code
                if (tweak) {
                    tweak.delegate = delegate
                    tweak.resolveStrategy = Closure.DELEGATE_FIRST
                    tweak.call()
                }
            }
        }
    }

    @Requires({ Hardening141ShellSpec.gitBash() })
    def "Git Bash runs a script from stdin, in the working directory, with its arguments and a scrubbed environment"() {
        given:
        def graph = gitBashGraph('''echo "dir=$(basename "$PWD")"
for a in "$@"; do echo "[$a]"; done
echo "host=[${COMPUTERNAME}]"
echo "named=[${GCU_NAMED}]"
echo made > made.txt
exit 0''') {
            args 'one', 'two-2', 'x=y', 'C:/tmp/a.txt'
            env 'GCU_NAMED', 'yes'
        }

        when:
        def result = run(graph)

        then:
        result.exitCode == 0
        lines(result.stdout) == ["dir=${dir.fileName}".toString(), '[one]', '[two-2]', '[x=y]', '[C:/tmp/a.txt]', 'host=[]', 'named=[yes]']
        Files.readString(dir.resolve('made.txt')).trim() == 'made'
    }

    private static final String GIT_BASH_SLEEPER = '''cat /proc/$$/winpid > bash.pid
sleep 60 &
cat /proc/$!/winpid > sleep.pid
wait'''

    @Requires({ Hardening141ShellSpec.gitBash() })
    def "Git Bash past its timeout is stopped, and so is the bash that runs the script"() {
        given: 'bash.exe starts MSYS bash, which starts sleep'
        def graph = gitBashGraph(GIT_BASH_SLEEPER) { processTimeout Duration.ofSeconds(4) }
        long started = System.nanoTime()

        when:
        def failure = failureOf { run(graph) }
        long seconds = (System.nanoTime() - started).intdiv(1_000_000_000L)

        then:
        causedBy(failure, TimeoutException)
        seconds < 30
        childStopped(Long.parseLong(Files.readString(dir.resolve('bash.pid')).trim()))

        cleanup:
        stopIfAlive(dir.resolve('sleep.pid'))
    }

    /**
     * A gap in 1.4.1, closed in 1.4.2. MSYS starts a background command through a short-lived fork
     * stub that exits at once, so the command's Windows parent is gone from the start and no walk of
     * the process tree reaches it (seen 2026-11-23: sleep.exe's parent pid had already exited).
     * 1.4.2 puts the run in a Windows Job Object, which holds every process started inside it.
     */
    @Requires({ Hardening141ShellSpec.gitBash() })
    def "Git Bash past its timeout: a background command it started is stopped too"() {
        given:
        def graph = gitBashGraph(GIT_BASH_SLEEPER) { processTimeout Duration.ofSeconds(4) }

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, TimeoutException)
        childStopped(Long.parseLong(Files.readString(dir.resolve('sleep.pid')).trim()))

        cleanup:
        stopIfAlive(dir.resolve('sleep.pid'))
    }

    // ------------------------------------------------------------------ 1.4.2: a Windows Job Object

    /** A PowerShell script that starts a child, which starts a grandchild and exits: the grandchild is orphaned. */
    private static final String ORPHANING = '''$child = Start-Process -FilePath powershell.exe -PassThru -NoNewWindow -ArgumentList '-NoProfile','-NonInteractive','-Command',"`$g = Start-Process -FilePath powershell.exe -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile','-Command','[System.Threading.Thread]::Sleep(60000)'; Set-Content -Path orphan.pid -Value `$g.Id"
$child.WaitForExit()
Write-Output "child exited"
'''

    @Requires({ WINDOWS })
    def "1.4.2: a process orphaned by its parent's exit is stopped when the script times out"() {
        given: 'the child that started it has exited, so no walk of the tree reaches it'
        def graph = powershell(ORPHANING + '[System.Threading.Thread]::Sleep(60000)') { processTimeout Duration.ofSeconds(8) }

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, TimeoutException)
        Files.exists(dir.resolve('orphan.pid'))
        childStopped(Long.parseLong(Files.readString(dir.resolve('orphan.pid')).trim()))

        cleanup:
        stopIfAlive(dir.resolve('orphan.pid'))
    }

    @Requires({ WINDOWS })
    def "1.4.2: when a script finishes, the background processes it left running are stopped (Will, 2026-11-23)"() {
        given: 'the orphan would otherwise run for a minute'
        def graph = powershell(ORPHANING + 'exit 0') { processTimeout Duration.ofSeconds(30) }
        long started = System.nanoTime()

        when:
        def result = run(graph)
        long seconds = (System.nanoTime() - started).intdiv(1_000_000_000L)

        then:
        result.exitCode == 0
        lines(result.stdout).contains('child exited')
        childStopped(Long.parseLong(Files.readString(dir.resolve('orphan.pid')).trim()))
        seconds < 20

        cleanup:
        stopIfAlive(dir.resolve('orphan.pid'))
    }

    @Requires({ Hardening141ShellSpec.gitBash() })
    def "1.4.2: when a Git Bash script finishes, a background command it left running is stopped"() {
        given:
        def graph = gitBashGraph('''sleep 60 &
cat /proc/$!/winpid > sleep.pid
echo done''')
        long started = System.nanoTime()

        when:
        def result = run(graph)
        long millis = (System.nanoTime() - started).intdiv(1_000_000L)

        then: 'and at once: the sleep holds the output pipe, so it is stopped before the output is read'
        result.exitCode == 0
        lines(result.stdout) == ['done']
        millis < 4000
        childStopped(Long.parseLong(Files.readString(dir.resolve('sleep.pid')).trim()))

        cleanup:
        stopIfAlive(dir.resolve('sleep.pid'))
    }

    @Requires({ WINDOWS })
    def "1.4.2: with Job Objects switched off, a timeout still stops the process and its linked children"() {
        given:
        Class runner = Class.forName('org.softwood.dag.task.shell.LocalShellRunner')
        runner.jobObjects = false
        def graph = powershell('''$p = Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile','-Command','[System.Threading.Thread]::Sleep(60000)' -PassThru -NoNewWindow
Set-Content -Path child.pid -Value $p.Id
[System.Threading.Thread]::Sleep(60000)''') { processTimeout Duration.ofSeconds(4) }

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, TimeoutException)
        childStopped(Long.parseLong(Files.readString(dir.resolve('child.pid')).trim()))

        cleanup:
        runner.jobObjects = true
        stopIfAlive(dir.resolve('child.pid'))
    }

    // ------------------------------------------------------------------ 1.4.2: keepBackground (Will, 2026-11-23)

    private static boolean aliveIn(Path pidFile) {
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).map { it.alive }.orElse(false)
    }

    @Requires({ WINDOWS })
    def "1.4.2: keepBackground keeps a background process running after the script, until the graph ends"() {
        given: 'a task that leaves a process running, and a later task that looks for it'
        String where = dir.toString()
        String code = ORPHANING + 'exit 0'
        Path pidFile = dir.resolve('orphan.pid')
        def graph = TaskGraph.build {
            allow 'process'
            interpreters 'powershell'
            workingDirectory where
            shellTask('start') {
                interpreter 'powershell'
                keepBackground true
                script code
            }
            serviceTask('check') {
                action { ctx, prev -> ctx.promiseFactory.executeAsync { [kept: prev.keptProcessIds, alive: aliveIn(pidFile)] } }
            }
            dependsOn('check', 'start')
        }

        when:
        def result = run(graph)
        long orphan = Long.parseLong(Files.readString(pidFile).trim())

        then: 'it was running while the graph ran, and is named in the result'
        result.alive == true
        result.kept.contains(orphan)

        and: 'it is stopped now the graph has ended'
        childStopped(orphan)

        cleanup:
        stopIfAlive(pidFile)
    }

    @Requires({ WINDOWS })
    def "1.4.2: keepBackground does not keep anything when the script times out"() {
        given:
        def graph = powershell(ORPHANING + '[System.Threading.Thread]::Sleep(60000)') {
            keepBackground true
            processTimeout Duration.ofSeconds(8)
        }

        when:
        def failure = failureOf { run(graph) }

        then:
        causedBy(failure, TimeoutException)
        childStopped(Long.parseLong(Files.readString(dir.resolve('orphan.pid')).trim()))

        cleanup:
        stopIfAlive(dir.resolve('orphan.pid'))
    }

    @Requires({ WINDOWS })
    def "1.4.2: without keepBackground the result says nothing was kept"() {
        expect:
        run(powershell('Write-Output hi')).keptProcessIds == []
    }

    private static void stopIfAlive(Path pidFile) {
        if (!Files.exists(pidFile)) return
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent { it.destroyForcibly() }
    }

    @Requires({ Hardening141ShellSpec.gitBash() })
    def "Git Bash: a non-zero exit fails the task, with stderr in the message"() {
        given:
        def graph = gitBashGraph('echo "went wrong" >&2\nexit 4')

        when:
        def failure = failureOf { run(graph) }

        then:
        messages(failure).contains('exit code 4')
        messages(failure).contains('went wrong')
    }

    // ------------------------------------------------------------------ interpreter probes

    static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder('python', '-I', '-c', 'print(1)').redirectErrorStream(true).start()
            p.inputStream.text
            return p.waitFor() == 0
        } catch (Exception ignored) {
            return false
        }
    }
}
