package dev.sirius.cloud.driver.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    private static Version v(String text) {
        return Version.parse(text).orElseThrow();
    }

    @Test
    void readsVersionsWhereverTheyAre() {
        assertEquals("1.0.4", v("v1.0.4").toString());
        assertEquals("1.0.3", v("SiriusCloud 1.0.3").toString());
        assertEquals("1.1.0-SNAPSHOT", v("1.1.0-SNAPSHOT").toString());
        assertTrue(Version.parse("minecraft").isEmpty());
    }

    @Test
    void ordersByNumbersThenSuffix() {
        assertTrue(v("1.0.10").isNewerThan(v("1.0.9")));
        assertTrue(v("1.1").isNewerThan(v("1.0.9")));
        assertTrue(v("1.0.4").isNewerThan(v("1.0.4-SNAPSHOT")));
        assertFalse(v("1.0.4-SNAPSHOT").isNewerThan(v("1.0.4")));
        assertEquals(0, v("1.0").compareTo(v("1.0.0")));
    }
}
