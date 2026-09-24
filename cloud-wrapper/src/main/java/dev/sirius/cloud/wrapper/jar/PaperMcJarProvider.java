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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final String V3_BUILDS = "https://fill.papermc.io/v3/projects/%s/versions/%s/builds";
    private static final String V2_VERSION = "https://api.papermc.io/v2/projects/%s/versions/%s";
    private static final String V2_DOWNLOAD =
            "https://api.papermc.io/v2/projects/%s/versions/%s/builds/%d/downloads/%s";

    /** PaperMC asks API consumers to identify themselves. */
    private static final String USER_AGENT = "SiriusCloud/1.0 (+https://github.com/sirius/siriuscloud)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * One lock per cached jar, so a group starting several services at once
     * downloads it once rather than once per service.
     *
     * <p>Not an optimisation. Without it every concurrent start wrote to the
     * same temporary file and they corrupted each other, producing "Invalid or
     * corrupt jarfile" on servers whose only mistake was starting at the same
     * time as their siblings - and the first one to finish deleted the file the
     * others were still writing.
     */
    private static final Map<String, Object> DOWNLOAD_LOCKS = new ConcurrentHashMap<>();

    private final Path cacheDirectory;
    private final PaperVersionCatalog catalog;
    private final String project;

    public PaperMcJarProvider(Path jarDirectory, PaperVersionCatalog catalog, String project) {
        this.cacheDirectory = jarDirectory.resolve("cache");
        this.catalog = catalog;
        this.project = project;
    }

    @Override
    public String name() {
        return "papermc:" + project;
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
            throw new IOException("Could not resolve a " + project + " build for " + version
                    + " and nothing is cached: " + exception.getMessage(), exception);
        }

        Path target = cacheDirectory.resolve(jarName(version, ref.build));
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }

        // Serialised per jar: the second service to want this waits for the
        // first one's download instead of starting its own on top of it.
        synchronized (DOWNLOAD_LOCKS.computeIfAbsent(target.toString(), key -> new Object())) {
            // Re-checked inside the lock, because whoever held it before us was
            // very likely downloading exactly this.
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                return target;
            }
            download(ref, target, version);
        }
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

    /**
     * Fill API: an array of build objects.
     *
     * <p>The highest build number wins rather than whichever end of the array
     * it sits at. Relying on position got this wrong in both directions: the
     * array is ordered newest-first, so reading from the end selected the
     * <em>oldest</em> build every time and quietly shipped servers dozens of
     * builds behind. Build numbers are integers, so comparing them is safe —
     * unlike version strings, where {@code PaperVersionCatalog} has to trust
     * the API's ordering precisely because they cannot be compared.
     */
    private BuildRef fetchFromV3(String version, Integer requested) throws IOException, InterruptedException {
        JsonElement body = get(String.format(V3_BUILDS, project, version));

        JsonArray builds = body.isJsonArray()
                ? body.getAsJsonArray()
                : body.getAsJsonObject().getAsJsonArray("builds");
        if (builds == null || builds.isEmpty()) {
            throw new IOException("No builds listed for " + version);
        }

        BuildRef best = null;

        for (JsonElement element : builds) {
            JsonObject entry = element.getAsJsonObject();

            int number = entry.has("id") ? entry.get("id").getAsInt() : entry.get("build").getAsInt();
            if (requested != null && number != requested) {
                continue;
            }
            if (best != null && number <= best.build) {
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

            best = new BuildRef(number, download.get("url").getAsString(), sha256);
        }

        if (best == null) {
            throw new IOException(requested == null
                    ? "No usable build listed for " + version
                    : "Build " + requested + " not found for " + version);
        }
        return best;
    }

    /** Legacy v2 API: build numbers only, download URL assembled by convention. */
    private BuildRef fetchFromV2(String version, Integer requested) throws IOException, InterruptedException {
        JsonObject body = get(String.format(V2_VERSION, project, version)).getAsJsonObject();
        JsonArray builds = body.getAsJsonArray("builds");
        if (builds == null || builds.isEmpty()) {
            throw new IOException("No builds listed for " + version);
        }

        // Highest, not last, for the same reason as above.
        int number = requested;
        if (requested == null) {
            number = Integer.MIN_VALUE;
            for (JsonElement element : builds) {
                number = Math.max(number, element.getAsInt());
            }
        }
        String fileName = project + "-" + version + "-" + number + ".jar";
        return new BuildRef(number, String.format(V2_DOWNLOAD, project, version, number, fileName), null);
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

    private void download(BuildRef ref, Path target, String version) throws IOException {
        LOGGER.info("Downloading {} {} build {} ...", project, version, ref.build);

        // Download beside the target, then move: an interrupted download must
        // never leave a half-written jar that looks like a valid cache entry.
        // The name is unique per attempt so that two downloads of the same jar
        // - from a second wrapper sharing this directory, or a retry racing a
        // straggler - cannot write to one file or delete each other's.
        Path temporary = target.resolveSibling(
                target.getFileName() + "." + java.util.UUID.randomUUID() + ".part");

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
                    .filter(path -> path.getFileName().toString().startsWith(project + "-" + version + "-"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()));
        }
    }

    private String jarName(String version, int build) {
        return project + "-" + version + "-" + build + ".jar";
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
