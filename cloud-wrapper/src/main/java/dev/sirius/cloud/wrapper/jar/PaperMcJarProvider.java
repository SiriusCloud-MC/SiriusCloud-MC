package dev.sirius.cloud.wrapper.jar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.paper.PaperVersionCatalog;

import java.io.IOException;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Downloads Paper server jars and caches them under {@code local/jars/cache/}.
 *
 * <p>One fetch per build, not per service start: a cache hit is the normal path
 * once a group has started for the first time.
 *
 * <p><strong>On the API:</strong> PaperMC has moved its download API over time
 * (the {@code v2} endpoints on {@code api.papermc.io} were superseded by the
 * {@code v3} "Fill" API). Both are attempted here and the JSON is navigated
 * defensively rather than mapped onto rigid model classes, so a field being
 * added or renamed degrades to the {@link LocalJarProvider} fallback instead of
 * taking the cloud down. Verify the live endpoints if downloads start failing.
 */
public final class PaperMcJarProvider implements JarProvider {

    private static final CloudLogger LOGGER = CloudLogger.of(PaperMcJarProvider.class);

    private static final String V3_BUILDS = "https://fill.papermc.io/v3/projects/paper/versions/%s/builds";
    private static final String V2_VERSION = "https://api.papermc.io/v2/projects/paper/versions/%s";
    private static final String V2_DOWNLOAD =
            "https://api.papermc.io/v2/projects/paper/versions/%s/builds/%d/downloads/%s";

    /** PaperMC asks API consumers to identify themselves. */
    private static final String USER_AGENT = "SiriusCloud/1.0 (+https://github.com/sirius/siriuscloud)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Path cacheDirectory;
    private final PaperVersionCatalog catalog;

    public PaperMcJarProvider(Path jarDirectory, PaperVersionCatalog catalog) {
        this.cacheDirectory = jarDirectory.resolve("cache");
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "papermc";
    }

    @Override
    public Path resolve(String requestedVersion, String build) throws IOException {
        Files.createDirectories(cacheDirectory);

        // Turns "latest" into a concrete version and rejects anything PaperMC
        // does not publish, so a typo in a group config fails with the list of
        // valid versions rather than a bare 404 from the download endpoint.
        String version = catalog.resolve(requestedVersion);

        Integer requested = "latest".equalsIgnoreCase(build) ? null : parseBuild(build);

        // A pinned build that is already cached needs no network at all.
        if (requested != null) {
            Path cached = cacheDirectory.resolve(jarName(version, requested));
            if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
                return cached;
            }
        }

        BuildRef ref;
        try {
            ref = fetchBuild(version, requested);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Optional<Path> cached = newestCached(version);
            if (cached.isPresent()) {
                LOGGER.warn("PaperMC lookup failed ({}), using cached {}",
                        exception.getMessage(), cached.get().getFileName());
                return cached.get();
            }
            throw new IOException("Could not resolve a Paper build for " + version
                    + " and nothing is cached: " + exception.getMessage(), exception);
        }

        Path target = cacheDirectory.resolve(jarName(version, ref.build));
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }

        download(ref, target);
        return target;
    }

    private BuildRef fetchBuild(String version, Integer requested) throws IOException, InterruptedException {
        try {
            return fetchFromV3(version, requested);
        } catch (IOException exception) {
            LOGGER.debug("Fill (v3) lookup failed: {}, trying the v2 API", exception.getMessage());
            return fetchFromV2(version, requested);
        }
    }

    /** Fill API: an array of build objects, newest last. */
    private BuildRef fetchFromV3(String version, Integer requested) throws IOException, InterruptedException {
        JsonElement body = get(String.format(V3_BUILDS, version));

        JsonArray builds = body.isJsonArray()
                ? body.getAsJsonArray()
                : body.getAsJsonObject().getAsJsonArray("builds");
        if (builds == null || builds.isEmpty()) {
            throw new IOException("No builds listed for " + version);
        }

        for (int i = builds.size() - 1; i >= 0; i--) {
            JsonObject entry = builds.get(i).getAsJsonObject();
            int number = entry.has("id") ? entry.get("id").getAsInt() : entry.get("build").getAsInt();
            if (requested != null && number != requested) {
                continue;
            }

            JsonObject downloads = entry.getAsJsonObject("downloads");
            if (downloads == null) {
                continue;
            }

            // The key has been both "server:default" and "application".
            JsonObject download = null;
            for (String key : new String[]{"server:default", "application", "server"}) {
                if (downloads.has(key)) {
                    download = downloads.getAsJsonObject(key);
                    break;
                }
            }
            if (download == null || !download.has("url")) {
                continue;
            }

            String sha256 = null;
            if (download.has("checksums") && download.getAsJsonObject("checksums").has("sha256")) {
                sha256 = download.getAsJsonObject("checksums").get("sha256").getAsString();
            }

            return new BuildRef(number, download.get("url").getAsString(), sha256);
        }

        throw new IOException("Build " + requested + " not found for " + version);
    }

    /** Legacy v2 API: build numbers only, download URL assembled by convention. */
    private BuildRef fetchFromV2(String version, Integer requested) throws IOException, InterruptedException {
        JsonObject body = get(String.format(V2_VERSION, version)).getAsJsonObject();
        JsonArray builds = body.getAsJsonArray("builds");
        if (builds == null || builds.isEmpty()) {
            throw new IOException("No builds listed for " + version);
        }

        int number = requested != null ? requested : builds.get(builds.size() - 1).getAsInt();
        String fileName = "paper-" + version + "-" + number + ".jar";
        return new BuildRef(number, String.format(V2_DOWNLOAD, version, number, fileName), null);
    }

    private JsonElement get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return JsonParser.parseString(response.body());
    }

    private void download(BuildRef ref, Path target) throws IOException {
        LOGGER.info("Downloading Paper build {} ...", ref.build);

        // Download beside the target, then move: an interrupted download must
        // never leave a half-written jar that looks like a valid cache entry.
        Path temporary = target.resolveSibling(target.getFileName() + ".part");

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ref.url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();

            HttpResponse<Path> response = http.send(
                    request, HttpResponse.BodyHandlers.ofFile(temporary));

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + ref.url);
            }

            if (ref.sha256 != null) {
                String actual = sha256(temporary);
                if (!actual.equalsIgnoreCase(ref.sha256)) {
                    throw new IOException("Checksum mismatch for build " + ref.build
                            + " (expected " + ref.sha256 + ", got " + actual + ")");
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

    private Optional<Path> newestCached(String version) throws IOException {
        if (Files.notExists(cacheDirectory)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(cacheDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("paper-" + version + "-"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()));
        }
    }

    private static String jarName(String version, int build) {
        return "paper-" + version + "-" + build + ".jar";
    }

    private static int parseBuild(String build) throws IOException {
        try {
            return Integer.parseInt(build.trim());
        } catch (NumberFormatException exception) {
            throw new IOException("Build must be a number or 'latest', got '" + build + "'");
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private record BuildRef(int build, String url, String sha256) {
    }
}
