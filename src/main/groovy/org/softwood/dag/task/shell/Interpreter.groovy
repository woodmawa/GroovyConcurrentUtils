package org.softwood.dag.task.shell

import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * 1.4.1 (decision D2): the interpreters a ShellTask can run, how the script reaches each one, and
 * the destructive patterns each is checked for.
 *
 * <p>The script never travels on the command line. PowerShell and cmd read it from a temporary file
 * (neither reads a whole script from stdin reliably); bash and Python read it from stdin. Arguments
 * follow on the command line as a list, with no shell in between, except where noted in
 * {@link ShellGuard}.</p>
 *
 * <p>The tripwires are a reduction, not a boundary: they catch the obvious destructive command
 * written by mistake or steered in by content Claude read, not a determined script. The real
 * controls are the working directory, the scrubbed environment and the operating system account.</p>
 */
@CompileStatic
enum Interpreter {

    POWERSHELL('powershell', ['powershell', 'pwsh', 'ps1'], '.ps1'),
    CMD('cmd', ['cmd', 'bat'], '.cmd'),
    BASH('bash', ['bash', 'sh'], null),
    PYTHON('python', ['python', 'py', 'python3'], null)

    static final boolean WINDOWS = System.getProperty('os.name', '').toLowerCase().contains('win')

    /** The name a flow uses. */
    final String id
    final List<String> aliases
    /** The file suffix when the script goes by file; null when it goes by stdin. */
    final String fileSuffix

    Interpreter(String id, List<String> aliases, String fileSuffix) {
        this.id = id
        this.aliases = aliases
        this.fileSuffix = fileSuffix
    }

    /** The interpreter a flow named, or null. */
    static Interpreter of(String name) {
        if (!name) return null
        String n = name.trim().toLowerCase()
        for (Interpreter i : values()) {
            if (i.id == n || i.aliases.contains(n)) return i
        }
        return null
    }

    static List<String> ids() {
        List<String> all = []
        for (Interpreter i : values()) all << i.id
        return all
    }

    boolean isViaStdin() {
        return fileSuffix == null
    }

    /** The executable used when the graph names no path for this interpreter. */
    String defaultExecutable() {
        switch (this) {
            case POWERSHELL: return WINDOWS ? 'powershell.exe' : 'pwsh'
            case CMD: return 'cmd.exe'
            case BASH: return 'bash'
            case PYTHON: return WINDOWS ? 'python' : 'python3'
        }
        return id
    }

    /** The command line. {@code scriptFile} is null for an interpreter that reads stdin. */
    List<String> command(String executable, File scriptFile, List<String> args) {
        List<String> cmd = [executable]
        switch (this) {
            case POWERSHELL:
                cmd.addAll(['-NoProfile', '-NonInteractive', '-NoLogo', '-ExecutionPolicy', 'Bypass', '-File', scriptFile.absolutePath])
                break
            case CMD:
                cmd.addAll(['/d', '/q', '/v:off', '/c', scriptFile.absolutePath])
                break
            case BASH:
                cmd.addAll(['--noprofile', '--norc', '-s', '--'])
                break
            case PYTHON:
                cmd.addAll(['-I', '-X', 'utf8', '-'])
                break
        }
        cmd.addAll(args ?: [])
        return cmd
    }

    /** The bytes the interpreter is given: the script with a short preamble that fixes the output encoding. */
    byte[] scriptBytes(String script) {
        String text = script ?: ''
        switch (this) {
            case POWERSHELL:
                String body = '[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false\r\n' +
                        "\$ProgressPreference = 'SilentlyContinue'\r\n" + text
                byte[] bom = [(byte) 0xEF, (byte) 0xBB, (byte) 0xBF] as byte[]
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8)
                byte[] all = new byte[bom.length + bytes.length]
                System.arraycopy(bom, 0, all, 0, bom.length)
                System.arraycopy(bytes, 0, all, bom.length, bytes.length)
                return all
            case CMD:
                return ('@echo off\r\nchcp 65001 >nul\r\n' + text.replaceAll('\r?\n', '\r\n')).getBytes(StandardCharsets.UTF_8)
            case BASH:
                return (text.replace('\r\n', '\n') + '\n').getBytes(StandardCharsets.UTF_8)
            default:
                return text.getBytes(StandardCharsets.UTF_8)
        }
    }

    // ------------------------------------------------------------------ tripwires

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE

    /** Before a command word: start of line, space, or a shell separator. */
    private static final String B = '(?:^|[\\s;|&({`])'
    /** After a command word: end, space, or a separator. */
    private static final String E = '(?=$|[\\s;|&)}])'

    private static Pattern p(String regex) { Pattern.compile(regex, FLAGS) }

    private static final Map<Interpreter, Map<String, Pattern>> TRIPWIRES = [
            (POWERSHELL): [
                    'Remove-Item'              : p('\\bRemove-Item\\b'),
                    'a delete alias'           : p(B + '(?:rm|del|erase|rd|rmdir|ri)' + E),
                    'Clear-Content/Clear-Item' : p('\\bClear-(?:\\w*Content|Item|Disk|RecycleBin)\\b'),
                    'Stop/Restart-Computer'    : p('\\b(?:Stop|Restart)-Computer\\b'),
                    'Stop-Process'             : p('\\bStop-Process\\b|' + B + '(?:kill|spps)' + E),
                    'Format-Volume/partitions' : p('\\b(?:Format-Volume|Remove-Partition|Initialize-Disk)\\b'),
                    'Invoke-Expression'        : p('\\bInvoke-Expression\\b|' + B + 'iex(?=$|[\\s;|&)}(\'"])'),
                    'Set-ExecutionPolicy'      : p('\\bSet-ExecutionPolicy\\b'),
            ],
            (CMD)       : [
                    'a delete command'         : p(B + '(?:del|erase|rd|rmdir)' + E),
                    'format/disk tools'        : p(B + '(?:format|diskpart|bcdedit|cipher|vssadmin|wmic)' + E),
                    'shutdown'                 : p(B + 'shutdown' + E),
                    'ownership/permissions'    : p(B + '(?:takeown|icacls|cacls)' + E),
                    'reg delete/add/import'    : p('\\breg(?:\\.exe)?\\s+(?:delete|add|import|restore)\\b'),
                    'taskkill'                 : p(B + 'taskkill' + E),
            ],
            (BASH)      : [
                    'rm'                       : p(B + 'rm' + E),
                    'chmod/chown'              : p(B + '(?:chmod|chown|chgrp)' + E),
                    'shutdown/reboot'          : p(B + '(?:shutdown|reboot|halt|poweroff)' + E),
                    'kill'                     : p(B + '(?:kill|killall|pkill)' + E),
                    'mkfs/dd/shred'            : p(B + '(?:mkfs[.\\w]*|shred|wipefs)' + E + '|\\bdd\\s+[^\\n]*\\bof='),
                    'a fork bomb'              : p(':\\s*\\(\\s*\\)\\s*\\{'),
                    'a download piped to a shell': p('\\b(?:curl|wget)\\b[^\\n|]*\\|\\s*(?:sudo\\s+)?(?:ba|z|da|k)?sh\\b'),
                    'a write to a disk device' : p('>\\s*/dev/(?:sd|nvme|hd|disk)'),
            ],
            (PYTHON)    : [
                    'shutil.rmtree'            : p('\\bshutil\\s*\\.\\s*rmtree\\b'),
                    'an os delete/exec call'   : p('\\bos\\s*\\.\\s*(?:remove|unlink|rmdir|removedirs|system|popen|kill|killpg|exec\\w*|spawn\\w*|fork\\w*)\\b'),
                    'subprocess'               : p('\\bsubprocess\\b'),
                    'ctypes'                   : p('\\bctypes\\b'),
                    'a path delete'            : p('\\.\\s*(?:unlink|rmdir)\\s*\\('),
            ],
    ] as Map<Interpreter, Map<String, Pattern>>

    /** Elevation and interactive sign-in: refused for every interpreter, trusted or not. */
    private static final Map<String, Pattern> ELEVATION = [
            'sudo/doas/runas'   : p(B + '(?:sudo|doas|runas|pkexec)' + E),
            'Start-Process -Verb RunAs': p('-Verb\\s+[\'"]?RunAs\\b'),
            'su'                : p(B + 'su' + '(?=$|\\s+(?:-|root\\b|\\w+\\s*$))'),
    ] as Map<String, Pattern>

    /** The tripwire the script matches, or null. */
    String tripwire(String script) {
        Map<String, Pattern> mine = TRIPWIRES[this]
        for (Map.Entry<String, Pattern> e : mine.entrySet()) {
            if (e.value.matcher(script ?: '').find()) return e.key
        }
        return null
    }

    /** The elevation pattern the script matches, or null. */
    static String elevation(String script) {
        for (Map.Entry<String, Pattern> e : ELEVATION.entrySet()) {
            if (e.value.matcher(script ?: '').find()) return e.key
        }
        return null
    }
}
