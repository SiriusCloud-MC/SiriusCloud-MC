package dev.sirius.cloud.node.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileBackendTest {

    @TempDir
    Path root;

    @Test
    void keysRoundTripThroughFileNames() {
        for (String key : new String[]{"steve", "Steve", "a:b/c\\d", "con", "ünïcødé", "x.json", "100%"}) {
            assertEquals(key, JsonFileBackend.decode(JsonFileBackend.encode(key)), key);
        }
    }

    @Test
    void keysDifferingOnlyInCaseGetDifferentFiles() {
        // Windows would otherwise treat these as one file and the second write
        // would silently replace the first.
        assertNotEquals(
                JsonFileBackend.encode("Steve").toLowerCase(),
                JsonFileBackend.encode("steve").toLowerCase());
    }

    @Test
    void encodedNamesAvoidCharactersEitherPlatformForbids() {
        String encoded = JsonFileBackend.encode("a<b>c:d\"e/f\\g|h?i*j");
        for (char forbidden : "<>:\"/\\|?*".toCharArray()) {
            assertFalse(encoded.indexOf(forbidden) >= 0, "contains " + forbidden);
        }
    }

    @Test
    void storesReadsListsAndDeletes() throws Exception {
        JsonFileBackend backend = new JsonFileBackend(root);

        backend.put("players", "Alice", "{\"a\":1}");
        backend.put("players", "alice", "{\"a\":2}");

        assertEquals("{\"a\":1}", backend.get("players", "Alice").orElseThrow());
        assertEquals("{\"a\":2}", backend.get("players", "alice").orElseThrow());
        assertEquals(2, backend.count("players"));
        assertEquals(Map.of("Alice", "{\"a\":1}", "alice", "{\"a\":2}"), backend.all("players"));

        assertTrue(backend.delete("players", "Alice"));
        assertFalse(backend.delete("players", "Alice"));
        assertEquals(1, backend.count("players"));
        assertTrue(backend.get("players", "Alice").isEmpty());
    }

    @Test
    void anEmptyCollectionIsEmptyRatherThanAnError() throws Exception {
        JsonFileBackend backend = new JsonFileBackend(root);
        assertEquals(0, backend.count("nothing"));
        assertTrue(backend.all("nothing").isEmpty());
    }
}
