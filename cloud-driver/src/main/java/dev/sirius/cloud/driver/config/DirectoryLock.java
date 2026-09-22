package dev.sirius.cloud.driver.config;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Stops two processes sharing one working directory.
 *
 * <p>Not hypothetical: a second wrapper started in a directory already in use
 * clears {@code local/running/} as part of its stale-directory cleanup, which
 * deletes the files of servers the <em>first</em> wrapper still has running.
 * Those servers keep going against unlinked files and lose their worlds on the
 * next save. An advisory lock turns that into a refusal to start.
 *
 * <p>{@link FileChannel#tryLock()} is enforced by the OS on both Linux and
 * Windows and is dropped automatically when the process dies, so a crash never
 * leaves a stale lock to be cleared by hand.
 */
public final class DirectoryLock implements AutoCloseable {

    private static final CloudLogger LOGGER = CloudLogger.of("DirectoryLock");

    private final FileChannel channel;
    private final FileLock lock;

    private DirectoryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Takes the lock, or fails saying why.
     *
     * @param lockFile where to place it, e.g. {@code wrapper/.lock}
     * @param what     name used in the error message
     * @throws IOException if another process already holds it
     */
    public static DirectoryLock acquire(Path lockFile, String what) throws IOException {
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);

        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException exception) {
            // Another thread of this same JVM already holds it. Same answer.
            channel.close();
            throw new IOException("This directory is already in use by this process");
        }

        if (lock == null) {
            channel.close();
            throw new IOException("Another " + what + " is already running in "
                    + lockFile.toAbsolutePath().getParent()
                    + ". Stop it first, or use a separate directory.");
        }

        return new DirectoryLock(channel, lock);
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException exception) {
            LOGGER.debug("Could not release the directory lock: {}", exception.getMessage());
        }
    }
}
