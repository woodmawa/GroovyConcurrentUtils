package org.softwood.dag.script

import org.softwood.dag.task.FileTaskSecurityConfig
import org.softwood.dag.task.FileTaskSecurityValidator
import org.softwood.dag.task.TaskContext

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * 1.4.0 (decision D2, level 2): what {@code allow 'files'} gives a restricted script, bound as
 * {@code files}.
 *
 * <p>A script still cannot name {@code File} or {@code Path}. It reads and writes text through this
 * object, and every path is checked by the same rules as an unconfigured {@code FileTask} (1.3.0,
 * D9): the strict limits, inside the graph's working directory (the JVM's if the graph names none)
 * and the directories the graph names with {@code allowedDirectories}. A relative path is taken
 * from the working directory. A path outside the scope is a {@link SecurityException}.</p>
 *
 * <p>1.4.1: a script reaches only the methods below ({@link ScriptCapability}); the scope and
 * limits this object holds cannot be read or changed from the script.</p>
 */
class ScriptFiles implements ScriptCapability {

    private static final Set<String> OFFERED = Collections.unmodifiableSet(
            new LinkedHashSet<String>(['read', 'readLines', 'exists', 'size', 'list', 'write', 'append']))

    @Override
    String capabilityName() { 'files' }

    @Override
    Set<String> offeredMethods() { OFFERED }

    private final File base
    private final FileTaskSecurityConfig config
    private final FileTaskSecurityValidator validator

    ScriptFiles(TaskContext ctx) {
        this.base = (ctx?.workingDirectory ?: new File(System.getProperty('user.dir'))).canonicalFile
        List<File> scope = [base]
        if (ctx?.fileAllowedDirectories) scope.addAll(ctx.fileAllowedDirectories)
        this.config = FileTaskSecurityConfig.strict(scope)
        this.validator = new FileTaskSecurityValidator(config)
    }

    /** The whole file as text (UTF-8). */
    String read(String path) {
        File f = existing(path)
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
    }

    /** The file's lines (UTF-8). */
    List<String> readLines(String path) {
        return read(path).readLines()
    }

    boolean exists(String path) {
        return checked(path).exists()
    }

    /** Size in bytes. */
    long size(String path) {
        return existing(path).length()
    }

    /** The names in a directory, sorted. */
    List<String> list(String directory) {
        File d = checked(directory)
        if (!d.isDirectory()) throw new IllegalArgumentException("not a directory: ${directory}")
        return (d.list() as List<String>).sort()
    }

    /** Write text (UTF-8), replacing the file. The directory must exist. */
    void write(String path, String text) {
        put(path, text, false)
    }

    /** Add text (UTF-8) to the end of the file. */
    void append(String path, String text) {
        put(path, text, true)
    }

    // ------------------------------------------------------------------

    private void put(String path, String text, boolean append) {
        File f = checked(path)
        byte[] bytes = (text ?: '').getBytes(StandardCharsets.UTF_8)
        long total = bytes.length + (append && f.exists() ? f.length() : 0L)
        if (total > config.maxFileSizeBytes) {
            throw new SecurityException("files: ${f.name} would be ${total} bytes, over the limit of ${config.maxFileSizeBytes}")
        }
        if (append) {
            f.append(bytes)
        } else {
            f.bytes = bytes
        }
    }

    private File existing(String path) {
        File f = checked(path)
        if (!f.isFile()) throw new IllegalArgumentException("no such file: ${path}")
        if (!validator.validateFileSize(f)) {
            throw new SecurityException("files: ${f.name} is over the size limit")
        }
        return f
    }

    private File checked(String path) {
        if (!path) throw new IllegalArgumentException('files: a path is required')
        File given = new File(path)
        File f = given.isAbsolute() ? given : new File(base, path)
        if (!validator.validateFilePath(f)) {
            throw new SecurityException("files: ${path} is outside the directories this graph may use")
        }
        if (f.exists() && !validator.validateSymlink(f)) {
            throw new SecurityException("files: ${path} is a symbolic link")
        }
        return f
    }
}
