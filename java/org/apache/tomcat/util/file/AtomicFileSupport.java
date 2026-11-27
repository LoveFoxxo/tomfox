/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.file;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Utilities for publishing a completely written file without exposing a partially written target. */
public final class AtomicFileSupport {

    private static final ReentrantLock[] TARGET_LOCKS = new ReentrantLock[64];

    static {
        for (int i = 0; i < TARGET_LOCKS.length; i++) {
            TARGET_LOCKS[i] = new ReentrantLock(true);
        }
    }

    /** An operation that may fail with an I/O exception. */
    @FunctionalInterface
    public interface IOOperation<T> {
        T run() throws IOException;
    }

    /**
     * Serialize a complete file transaction. A fixed lock array avoids retaining an unbounded map of historical paths.
     * Different paths may share a lock, which reduces concurrency but never correctness.
     *
     * @param target the file being modified
     * @param operation the transaction
     * @param <T> the result type
     *
     * @return the operation result
     *
     * @throws IOException if the operation fails
     */
    public static <T> T withLock(Path target, IOOperation<T> operation) throws IOException {
        Path key = target.toAbsolutePath().normalize();
        ReentrantLock lock = TARGET_LOCKS[(key.hashCode() & Integer.MAX_VALUE) % TARGET_LOCKS.length];
        lock.lock();
        try {
            return operation.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Create a unique temporary file beside the target so that the later move remains on the same file store.
     *
     * @param target the final target
     * @param suffix the temporary file suffix
     *
     * @return the newly created temporary file
     *
     * @throws IOException if the temporary file cannot be created
     */
    public static Path createTempFile(Path target, String suffix) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("Target has no parent directory: " + target);
        }
        return Files.createTempFile(parent, absoluteTarget.getFileName().toString() + '.', suffix);
    }

    /**
     * Replace a target with a completely written temporary file. When a backup is supplied and the target exists, the
     * original is copied to the backup first and is restored if the replacement fails.
     *
     * @param temporary the completely written source file
     * @param target the final target
     * @param backup an optional backup path, which must not alias the source or target
     * @param replaceExisting whether an existing target may be replaced
     *
     * @throws IOException if publication or restoration fails; restoration failures are suppressed on the original
     */
    public static void publish(Path temporary, Path target, Path backup, boolean replaceExisting) throws IOException {
        withLock(target, () -> {
            publishLocked(temporary.toAbsolutePath().normalize(), target.toAbsolutePath().normalize(),
                    backup == null ? null : backup.toAbsolutePath().normalize(), replaceExisting);
            return null;
        });
    }

    private static void publishLocked(Path temporary, Path target, Path backup, boolean replaceExisting)
            throws IOException {
        if (temporary.equals(target) || temporary.equals(backup) || target.equals(backup)) {
            throw new IOException("Temporary, target and backup paths must be distinct");
        }
        if (!Objects.equals(temporary.getParent(), target.getParent())) {
            throw new IOException("Temporary file must be in the target directory");
        }

        // Persist the completed contents before exposing the new directory entry.
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        boolean hadTarget = Files.exists(target);
        if (!replaceExisting && hadTarget) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (backup != null && hadTarget) {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }

        try {
            move(temporary, target, replaceExisting, true);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                move(temporary, target, replaceExisting, false);
            } catch (IOException failure) {
                restore(temporary, target, backup, hadTarget, failure);
                throw failure;
            }
        } catch (IOException failure) {
            restore(temporary, target, backup, hadTarget, failure);
            throw failure;
        }
    }

    private static void move(Path source, Path target, boolean replaceExisting, boolean atomic) throws IOException {
        CopyOption[] options;
        if (replaceExisting && atomic) {
            options = new CopyOption[] { StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE };
        } else if (replaceExisting) {
            options = new CopyOption[] { StandardCopyOption.REPLACE_EXISTING };
        } else if (atomic) {
            options = new CopyOption[] { StandardCopyOption.ATOMIC_MOVE };
        } else {
            options = new CopyOption[0];
        }
        Files.move(source, target, options);
    }

    private static void restore(Path temporary, Path target, Path backup, boolean hadTarget, IOException failure) {
        try {
            if (hadTarget && backup != null) {
                Path recovery = createTempFile(target, ".restore");
                try {
                    Files.copy(backup, recovery, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    try {
                        move(recovery, target, true, true);
                    } catch (AtomicMoveNotSupportedException e) {
                        move(recovery, target, true, false);
                    }
                } finally {
                    Files.deleteIfExists(recovery);
                }
            } else if (!hadTarget && !Files.exists(temporary)) {
                // A failed first publication must not leave an unacknowledged new file behind.
                Files.deleteIfExists(target);
            }
        } catch (IOException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private AtomicFileSupport() {
    }
}
