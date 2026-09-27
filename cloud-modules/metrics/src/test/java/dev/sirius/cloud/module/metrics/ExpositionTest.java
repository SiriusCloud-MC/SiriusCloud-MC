package dev.sirius.cloud.module.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpositionTest {

    @Test
    void groupsSamplesUnderOneHeader() {
        String text = new Exposition()
                .gauge("players", "Players.", 3, "group", "Lobby")
                .gauge("uptime", "Uptime.", 1.5)
                .gauge("players", "Players.", 7, "group", "BedWars")
                .render();
        assertEquals("""
                # HELP players Players.
                # TYPE players gauge
                players{group="Lobby"} 3
                players{group="BedWars"} 7
                # HELP uptime Uptime.
                # TYPE uptime gauge
                uptime 1.5
                """, text);
    }

    @Test
    void escapesLabelValues() {
        assertEquals("a\\\"b\\\\c\\n", Exposition.escape("a\"b\\c\n"));
    }

    @Test
    void formatsSpecialValues() {
        assertEquals("NaN", Exposition.format(Double.NaN));
        assertEquals("+Inf", Exposition.format(Double.POSITIVE_INFINITY));
        assertEquals("42", Exposition.format(42.0));
    }
}
