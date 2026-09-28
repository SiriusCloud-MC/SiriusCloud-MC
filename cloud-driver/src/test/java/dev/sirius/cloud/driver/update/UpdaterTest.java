package dev.sirius.cloud.driver.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    private static final Updater.Layout NODE = new Updater.Layout() {
        @Override
        public String mainAsset() {
            return "cloud-node.jar";
        }

        @Override
        public Optional<String> target(String name) {
            return name.equals("cloud-node.jar") ? Optional.of(name) : Optional.empty();
        }
    };

    private static Updater updater(String current, boolean preReleases) {
        UpdateSettings settings = new com.google.gson.Gson()
                .fromJson("{\"preReleases\":" + preReleases + "}", UpdateSettings.class);
        return new Updater(Path.of("."), Version.parse(current).orElseThrow(), settings, NODE);
    }

    private static String release(String tag, String name, boolean pre, String... assets) {
        StringBuilder list = new StringBuilder();
        for (String asset : assets) {
            list.append(list.isEmpty() ? "" : ",")
                    .append("{\"name\":\"").append(asset).append("\",\"browser_download_url\":\"https://x/")
                    .append(asset).append("\"}");
        }
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"" + name + "\",\"draft\":false,\"prerelease\":" + pre
                + ",\"html_url\":\"https://x\",\"assets\":[" + list + "]}";
    }

    private static JsonArray list(String... releases) {
        return JsonParser.parseString("[" + String.join(",", releases) + "]").getAsJsonArray();
    }

    @Test
    void picksTheNewestInstallableRelease() {
        JsonArray releases = list(
                release("v1.0.6", "", false, "cloud-node.jar"),               // no checksums
                release("v1.0.5", "", false, "cloud-node.jar", "SHA256SUMS"),
                release("v1.0.4", "", false, "cloud-node.jar", "SHA256SUMS"),
                release("SiriusCloud", "SiriusCloud 1.0.3", true, "dist.zip"));
        assertEquals("1.0.5", updater("1.0.0-SNAPSHOT", false).pick(releases).orElseThrow().version().toString());
    }

    @Test
    void nothingWhenAlreadyCurrent() {
        JsonArray releases = list(release("v1.0.5", "", false, "cloud-node.jar", "SHA256SUMS"));
        assertTrue(updater("1.0.5", false).pick(releases).isEmpty());
        assertTrue(updater("1.1.0", false).pick(releases).isEmpty());
    }

    @Test
    void preReleasesOnlyWhenAskedFor() {
        JsonArray releases = list(release("v1.1.0-rc1", "", true, "cloud-node.jar", "SHA256SUMS"));
        assertTrue(updater("1.0.5", false).pick(releases).isEmpty());
        assertEquals("1.1.0-rc1", updater("1.0.5", true).pick(releases).orElseThrow().version().toString());
    }

    @Test
    void readsSha256sumOutput() {
        String hex = "a".repeat(64);
        Map<String, String> sums = Updater.checksums(
                (hex + "  cloud-node.jar\n" + hex.toUpperCase() + " *cloud-wrapper.jar\nnot a line\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(hex, sums.get("cloud-node.jar"));
        assertEquals(hex, sums.get("cloud-wrapper.jar"));
        assertEquals(2, sums.size());
    }
}
