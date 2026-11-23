package org.softwood.dag.task.office

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File writing shared by the office adapters (GCU 2.0.1).
 *
 * <p>The adapters used to open the target with {@code new FileOutputStream(file)}, which truncates it first: a save
 * that failed part-way left an empty or broken file where the old one had been. {@link #writeAtomically} writes to a
 * temporary file beside the target and moves it into place only once the whole document has been written, so the
 * target is either the old file or the complete new one.</p>
 */
class OfficeFiles {

    /**
     * Write {@code target} by calling {@code writer} with an output stream on a temporary sibling, then moving the
     * sibling over the target. Parent directories are created. The temporary file is removed whatever happens.
     */
    static void writeAtomically(File target, Closure<?> writer) {
        Path t = target.toPath().toAbsolutePath()
        if (t.parent != null) Files.createDirectories(t.parent)
        Path tmp = Files.createTempFile(t.parent, '.' + t.fileName.toString() + '.', '.tmp')
        try {
            Files.newOutputStream(tmp).withCloseable { OutputStream out -> writer.call(out) }
            try {
                Files.move(tmp, t, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, t, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Throw a single {@link IllegalArgumentException} naming every problem, or return when there are none. */
    static void refuse(String who, List<String> problems) {
        if (problems) {
            throw new IllegalArgumentException("${who}: nothing written -- ${problems.join('; ')}".toString())
        }
    }
}
