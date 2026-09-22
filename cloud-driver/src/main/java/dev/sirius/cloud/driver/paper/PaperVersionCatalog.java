package dev.sirius.cloud.driver.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The list of Minecraft versions PaperMC publishes, fetched live.
 *
 * <p><strong>Nothing here parses or compares version strings.</strong> That is
 * deliberate and it is the whole design. Minecraft version strings have not
 * kept one shape over time, and any comparator written against the shape they
 * had when this was written would silently mis-order the moment that changes —
 * putting a newer release "before" an older one and resolving {@code latest} to
 * the wrong thing.
 *
 * <p>So the API's own ordering is treated as authoritative: newest last,
 * {@code latest} means the final entry, and "supported" means "present in the
 * list". A version range is expressed as an <em>index</em> into that list (see
 * {@link #from(String)}), never as a numeric comparison. New Minecraft releases
 * therefore work with no code change at all, whatever they end up being called.
 */
public final class PaperVersionCatalog {

    private static final CloudLogger LOGGER = CloudLogger.of("PaperVersions");

    private static final String V3_PROJECT = "https://fill.papermc.io/v3/projects/paper";
    private static final String V2_PROJECT = "https://api.papermc.io/v2/projects/paper";

    private static final String USER_AGENT = "SiriusCloud/1.0 (+https://github.com/sirius/siriuscloud)";

    /** Versions change rarely; re-fetching on every service start would be rude. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile List<String> cached = List.of();
    private volatile long cachedAt;

    /** Oldest first, newest last, exactly as PaperMC orders them. */
    public synchronized List<String> versions() throws IOException {
        if (!cached.isEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL.toMillis()) {
            return cached;
        }

        List<String> versions;
        try {
            versions = fetchFromV3();
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.debug("Fill (v3) version listing failed: {}, trying v2", exception.getMessage());
            try {
                versions = fetchFromV2();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Version lookup interrupted", interrupted);
            }
        }

        if (versions.isEmpty()) {
            throw new IOException("PaperMC returned no versions");
        }

        cached = List.copyOf(versions);
        cachedAt = System.currentTimeMillis();
        LOGGER.info("PaperMC publishes {} versions ({} .. {})",
                cached.size(), cached.get(0), cached.get(cached.size() - 1));
        return cached;
    }

    /** The newest published version. */
    public String latest() throws IOException {
        List<String> versions = versions();
        return versions.get(versions.size() - 1);
    }

    /**
     * Resolves {@code latest} to a concrete version, and validates anything else.
     *
     * @throws IOException if the version is not one PaperMC publishes
     */
    public String resolve(String version) throws IOException {
        if (version == null || version.isBlank() || "latest".equalsIgnoreCase(version)) {
            return latest();
        }

        List<String> versions = versions();
        for (String candidate : versions) {
            if (candidate.equalsIgnoreCase(version)) {
                return candidate;
            }
        }

        throw new IOException("PaperMC does not publish '" + version + "'. Newest is "
                + versions.get(versions.size() - 1) + "; run 'versions' for the full list.");
    }

    /**
     * Every version from {@code first} onwards, by position in the published list.
     *
     * <p>Index-based rather than comparison-based, so expressing "1.21.1 and
     * newer" needs no knowledge of how versions are numbered — only where
     * {@code 1.21.1} sits in PaperMC's own ordering. An unknown {@code first}
     * returns the whole list rather than nothing, because hiding every version
     * over a typo in a config file is the worse failure.
     */
    public List<String> from(String first) throws IOException {
        List<String> versions = versions();
        if (first == null || first.isBlank()) {
            return versions;
        }

        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).equalsIgnoreCase(first)) {
                return versions.subList(i, versions.size());
            }
        }

        LOGGER.debug("Minimum version '{}' is not in PaperMC's list, not filtering", first);
        return versions;
    }

    public boolean isCached() {
        return !cached.isEmpty();
    }

    /**
     * Fill API. The versions may arrive as a flat array or grouped by release
     * family, so both shapes are handled and neither is assumed.
     */
    private List<String> fetchFromV3() throws IOException, InterruptedException {
        JsonElement body = get(V3_PROJECT);
        JsonObject root = body.getAsJsonObject();

        JsonElement versionsElement = root.get("versions");
        if (versionsElement == null && root.has("project")) {
            versionsElement = root.getAsJsonObject("project").get("versions");
        }
        if (versionsElement == null) {
            throw new IOException("No 'versions' field in the project response");
        }

        List<String> versions = new ArrayList<>();

        if (versionsElement.isJsonArray()) {
            for (JsonElement element : versionsElement.getAsJsonArray()) {
                versions.add(readVersionName(element));
            }
        } else {
            // Grouped by family, e.g. {"1.21": ["1.21.1", ...], "1.20": [...]}.
            // Families are listed newest first, so reverse to keep the
            // oldest-first ordering the rest of this class relies on.
            JsonObject grouped = versionsElement.getAsJsonObject();
            List<List<String>> families = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : grouped.entrySet()) {
                List<String> family = new ArrayList<>();
                if (entry.getValue().isJsonArray()) {
                    for (JsonElement element : entry.getValue().getAsJsonArray()) {
                        family.add(readVersionName(element));
                    }
                }
                families.add(family);
            }
            for (int i = families.size() - 1; i >= 0; i--) {
                versions.addAll(families.get(i));
            }
        }

        versions.removeIf(version -> version == null || version.isBlank());
        return versions;
    }

    /** Legacy v2: {@code {"versions": ["1.8.8", ..., "1.21.4"]}}, oldest first. */
    private List<String> fetchFromV2() throws IOException, InterruptedException {
        JsonObject root = get(V2_PROJECT).getAsJsonObject();
        JsonArray array = root.getAsJsonArray("versions");
        if (array == null) {
            throw new IOException("No 'versions' field in the v2 project response");
        }

        List<String> versions = new ArrayList<>();
        for (JsonElement element : array) {
            versions.add(readVersionName(element));
        }
        versions.removeIf(version -> version == null || version.isBlank());
        return versions;
    }

    /** An entry may be a bare string or an object carrying the name. */
    private static String readVersionName(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : new String[]{"version", "id", "name"}) {
                if (object.has(key)) {
                    return object.get(key).getAsString();
                }
            }
            return null;
        }
        return element.getAsString();
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

    /** Best-effort lookup that never throws, for display code. */
    public Optional<String> latestQuietly() {
        try {
            return Optional.of(latest());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "PaperVersionCatalog{" + cached.size() + " versions cached}"
                .toLowerCase(Locale.ROOT);
    }
}
