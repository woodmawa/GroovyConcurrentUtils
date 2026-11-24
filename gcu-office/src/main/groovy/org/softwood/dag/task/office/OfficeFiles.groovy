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
            moveIntoPlace(tmp, t)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** GCU 3.0.0 (from FS 0.9.25 WriteUtils): attempts, and the linear backoff between them. 25+50+...+225 = 1125 ms. */
    static final int  MOVE_ATTEMPTS   = 10
    static final long MOVE_BACKOFF_MS = 25L

    /**
     * The rename, with a bounded retry. On Windows {@code Files.move(.., ATOMIC_MOVE)} throws AccessDeniedException
     * while ANY handle is open on the target -- Defender scanning the new file, the indexer, an editor -- and that
     * handle is usually gone milliseconds later. Without the retry the write was lost and reported as a failure (FS
     * found it as a "flaky" suite). Bounded so a permanent failure is still reported, soon and with its own reason;
     * a missing source is never retried.
     */
    private static void moveIntoPlace(Path tmp, Path target) {
        Exception last = null
        for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (java.nio.file.NoSuchFileException e) {
                throw e
            } catch (java.nio.file.FileSystemException e) {
                last = e
                if (attempt < MOVE_ATTEMPTS - 1) {
                    try { Thread.sleep(MOVE_BACKOFF_MS * (attempt + 1)) }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e }
                }
            }
        }
        throw last
    }

    /** Throw a single {@link IllegalArgumentException} naming every problem, or return when there are none. */
    static void refuse(String who, List<String> problems) {
        if (problems) {
            throw new IllegalArgumentException("${who}: nothing written -- ${problems.join('; ')}".toString())
        }
    }
}
