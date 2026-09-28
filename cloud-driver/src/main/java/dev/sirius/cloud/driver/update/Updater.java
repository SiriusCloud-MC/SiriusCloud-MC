package dev.sirius.cloud.driver.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finds new SiriusCloud releases on GitHub and stages them for installing.
 *
 * <p>Nothing here replaces a running jar - on Windows that is not even
 * possible. A release is downloaded into {@code local/updates/ready/}, laid
 * out like this directory, and the start scripts copy it into place before
 * the next start. Either every file of a release is staged and verified, or
 * none is: files are downloaded elsewhere first and only moved into
 * {@code ready/} once all of them match the release's {@code SHA256SUMS}.
 *
 * <p>A release without {@code SHA256SUMS} or without this component's jar is
 * not considered at all, which is also what keeps hand-made releases of a
 * zip from being mistaken for one this can install.
 */
public final class Updater {

    /** Which release files this component takes, and where they go. */
    public interface Layout {

        /** The jar that must be in a release for it to count, e.g. {@code cloud-node.jar}. */
        String mainAsset();

        /** Where an asset belongs relative to this directory, or empty if this component does not use it. */
        Optional<String> target(String assetName);
    }

    /** One release that could be installed. */
    public record Release(Version version, String tag, String page, Map<String, String> assets) {
    }

    static final String CHECKSUMS = "SHA256SUMS";

    private final Path root;
    private final Version current;
    private final UpdateSettings settings;
    private final Layout layout;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public Updater(Path root, Version current, UpdateSettings settings, Layout layout) {
        this.root = root.toAbsolutePath().normalize();
        this.current = current;
        this.settings = settings;
        this.layout = layout;
    }

    public Version current() {
        return current;
    }

    private Path updates() {
        return root.resolve("local").resolve("updates");
    }

    /** The newest installable release that is newer than this build, if there is one. */
    public Optional<Release> newest() throws IOException {
        // Overridable for testing against a stand-in; always GitHub otherwise.
        String api = System.getProperty("siriuscloud.updateApi", "https://api.github.com");
        String url = api + "/repos/" + settings.repository() + "/releases?per_page=20";
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode() + " for " + url);
        }
        return pick(JsonParser.parseString(response.body()).getAsJsonArray());
    }

    /** Chooses from GitHub's list of releases. Separate so it can be tested without GitHub. */
    Optional<Release> pick(JsonArray releases) {
        Release best = null;
        for (JsonElement element : releases) {
            JsonObject release = element.getAsJsonObject();
            if (bool(release, "draft") || (bool(release, "prerelease") && !settings.preReleases())) {
                continue;
            }
            Optional<Version> version = Version.parse(string(release, "tag_name"))
                    .or(() -> Version.parse(string(release, "name")));
            if (version.isEmpty() || (version.get().isPreRelease() && !settings.preReleases())) {
                continue;
            }
            Map<String, String> assets = new LinkedHashMap<>();
            for (JsonElement asset : release.getAsJsonArray("assets")) {
                JsonObject object = asset.getAsJsonObject();
                assets.put(string(object, "name"), string(object, "browser_download_url"));
            }
            if (!assets.containsKey(CHECKSUMS) || !assets.containsKey(layout.mainAsset())) {
                continue;
            }
            if (version.get().isNewerThan(current) && (best == null || version.get().isNewerThan(best.version()))) {
                best = new Release(version.get(), string(release, "tag_name"), string(release, "html_url"), assets);
            }
        }
        return Optional.ofNullable(best);
    }

    /** The version waiting in {@code ready/} for the next start, if any. */
    public Optional<Version> staged() {
        Path marker = updates().resolve("ready.version");
        if (Files.notExists(updates().resolve("ready")) || Files.notExists(marker)) {
            return Optional.empty();
        }
        try {
            return Version.parse(Files.readString(marker).trim());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    /** Downloads and verifies a release, then makes it the one installed on the next start. */
    public void stage(Release release) throws IOException {
        try {
            download(release);
        } finally {
            // Whatever a failed attempt left half-downloaded.
            deleteRecursively(updates().resolve("staging"));
        }
    }

    private void download(Release release) throws IOException {
        Map<String, String> sums = checksums(download(release.assets().get(CHECKSUMS)));
        Path staging = updates().resolve("staging");
        deleteRecursively(staging);
        Files.createDirectories(staging);

        int files = 0;
        for (Map.Entry<String, String> asset : release.assets().entrySet()) {
            Optional<String> target = layout.target(asset.getKey());
            if (target.isEmpty()) {
                continue;
            }
            String expected = sums.get(asset.getKey());
            if (expected == null) {
                throw new IOException(asset.getKey() + " is not listed in " + CHECKSUMS);
            }
            Path destination = staging.resolve(target.get()).normalize();
            if (!destination.startsWith(staging)) {
                throw new IOException("Refusing to place " + asset.getKey() + " outside this directory");
            }
            Files.createDirectories(destination.getParent());
            String actual = downloadTo(asset.getValue(), destination);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException(asset.getKey() + " does not match its checksum; not installing");
            }
            files++;
        }
        if (Files.notExists(staging.resolve(layout.target(layout.mainAsset()).orElseThrow()))) {
            throw new IOException("The release has no " + layout.mainAsset());
        }

        Path ready = updates().resolve("ready");
        deleteRecursively(ready);
        Files.move(staging, ready, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(updates().resolve("ready.version"), release.version().toString());
        if (files == 0) {
            throw new IOException("Nothing to install");
        }
    }

    private byte[] download(String url) throws IOException {
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed (" + response.statusCode() + "): " + url);
        }
        return response.body();
    }

    /** Streams a download to disk, hashing as it goes. Returns the SHA-256 in hex. */
    private String downloadTo(String url, Path destination) throws IOException {
        HttpResponse<InputStream> response = send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download failed (" + response.statusCode() + "): " + url);
        }
        try (DigestInputStream in = new DigestInputStream(response.body(), sha256())) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) throws IOException {
        HttpRequest withAgent = HttpRequest.newBuilder(request, (name, value) -> true)
                .header("User-Agent", "SiriusCloud-Updater/" + current)
                .timeout(Duration.ofMinutes(5))
                .build();
        try {
            return http.send(withAgent, body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", exception);
        }
    }

    /** {@code sha256sum} output: {@code <hex>  <name>}, or {@code <hex> *<name>} in binary mode. */
    static Map<String, String> checksums(byte[] file) {
        Map<String, String> sums = new HashMap<>();
        for (String line : new String(file, StandardCharsets.UTF_8).split("\\R")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && parts[0].matches("[0-9a-fA-F]{64}")) {
                sums.put(parts[1].startsWith("*") ? parts[1].substring(1) : parts[1],
                        parts[0].toLowerCase(Locale.ROOT));
            }
        }
        return sums;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

        private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }
}
