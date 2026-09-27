package dev.sirius.cloud.driver.paper;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The shared part of a catalog: caching, {@code latest}, validation, ranges.
 *
 * <p>A subclass only has to fetch the list, oldest first, and say which entries
 * are stable. Stability is the subclass's call because sources differ: Fabric
 * says so in its data, the others only in the version string.
 */
public abstract class SimpleVersionCatalog implements VersionCatalog {

    /** Versions change rarely; re-fetching on every service start would be rude. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    protected static final String USER_AGENT = "SiriusCloud/1.0 (+https://github.com/SiriusCloud-MC/SiriusCloud-MC)";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String project;
    private volatile List<String> cached = List.of();
    private volatile long cachedAt;

    protected SimpleVersionCatalog(String project) {
        this.project = project;
    }

    /** Every version, oldest first. */
    protected abstract List<String> fetch() throws IOException, InterruptedException;

    protected boolean isStable(String version) {
        return PaperVersionCatalog.isStable(version);
    }

    @Override
    public String project() {
        return project;
    }

    @Override
    public synchronized List<String> versions() throws IOException {
        if (!cached.isEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL.toMillis()) {
            return cached;
        }
        try {
            List<String> fetched = fetch();
            if (fetched.isEmpty()) {
                throw new IOException(project + " publishes no versions");
            }
            cached = List.copyOf(fetched);
            cachedAt = System.currentTimeMillis();
            return cached;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Version lookup interrupted", exception);
        }
    }

    @Override
    public String latest() throws IOException {
        List<String> versions = versions();
        for (int i = versions.size() - 1; i >= 0; i--) {
            if (isStable(versions.get(i))) {
                return versions.get(i);
            }
        }
        return versions.get(versions.size() - 1);
    }

    @Override
    public String newestPublished() throws IOException {
        List<String> versions = versions();
        return versions.get(versions.size() - 1);
    }

    @Override
    public String resolve(String version) throws IOException {
        if (version == null || version.isBlank() || version.equalsIgnoreCase("latest")) {
            return latest();
        }
        for (String candidate : versions()) {
            if (candidate.equalsIgnoreCase(version)) {
                return candidate;
            }
        }
        throw new IOException(project + " does not publish '" + version + "'. Newest is " + latest()
                + "; run 'versions " + project + "' for the list.");
    }

    @Override
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
        return versions;
    }

    protected static JsonElement getJson(String url) throws IOException, InterruptedException {
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
        return JsonParser.parseString(response.body());
    }
}
