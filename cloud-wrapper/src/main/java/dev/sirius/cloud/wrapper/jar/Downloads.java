package dev.sirius.cloud.wrapper.jar;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Downloads into a cache, safely under concurrency.
 *
 * <p>The same three protections the Paper provider needed after a group with a
 * minimum of five on a cold cache started five downloads of one jar into one
 * temporary file:
 * <ul>
 *   <li>one lock per target, so the first caller downloads and the rest wait
 *       and then find it cached;
 *   <li>a unique temporary name per attempt, so two writers never share a file;
 *   <li>a move into place only after the checksum passes, so a half-written or
 *       corrupted file never looks like a valid cache entry.
 * </ul>
 */
final class Downloads {

    private static final CloudLogger LOGGER = CloudLogger.of("Downloads");

    static final String USER_AGENT = "SiriusCloud/1.0 (+https://github.com/SiriusCloud-MC/SiriusCloud-MC)";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private Downloads() {
    }

    /**
     * Makes sure {@code target} exists, downloading it if not.
     *
     * @param algorithm e.g. {@code SHA-512}, or null when the source offers no hash
     * @param expected  the hash in hex, or null
     */
    static Path ensure(String url, Path target, String algorithm, String expected) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        ReentrantLock lock = LOCKS.computeIfAbsent(target.toAbsolutePath().normalize(), key -> new ReentrantLock());
        lock.lock();
        try {
            // Whoever held the lock before us has very likely just finished it.
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                return target;
            }
            Files.createDirectories(target.getParent());
            download(url, target, algorithm, expected);
            return target;
        } finally {
            lock.unlock();
        }
    }

    private static void download(String url, Path target, String algorithm, String expected) throws IOException {
        LOGGER.info("Downloading {} ...", target.getFileName());
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
            }
            if (algorithm != null && expected != null && !expected.isBlank()) {
                String actual = hash(temporary, algorithm);
                if (!actual.equalsIgnoreCase(expected)) {
                    throw new IOException("Checksum mismatch for " + target.getFileName()
                            + " (" + algorithm + " expected " + expected + ", got " + actual + ")");
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Cached {} ({} MB)", target.getFileName(), Files.size(target) / (1024 * 1024));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String hash(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException(algorithm + " is unavailable", exception);
        }
    }

    static String getString(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", exception);
        }
    }
}
