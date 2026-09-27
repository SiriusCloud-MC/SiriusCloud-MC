package dev.sirius.cloud.api.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TextTest {

    @Test
    void escapedInputCannotOpenATag() {
        String hostile = "<click:run_command:/op me>free diamonds</click>";
        String escaped = Text.escape(hostile);
        // Every '<' must be preceded by a backslash, so none can start a tag.
        for (int i = 0; i < escaped.length(); i++) {
            if (escaped.charAt(i) == '<') {
                assertEquals('\\', escaped.charAt(i - 1));
            }
        }
    }

    @Test
    void aBackslashCannotBeUsedToUnescapeTheNextCharacter() {
        // "\<" typed by a player must stay a literal backslash then a literal
        // '<', not become an escaped '\' followed by a live tag.
        assertEquals("\\\\\\<b>", Text.escape("\\<b>"));
    }

    @Test
    void plainDropsTagsAndRestoresEscapedText() {
        assertEquals("hello <world>", Text.plain("<red>hello " + Text.escape("<world>") + "</red>"));
        assertFalse(Text.plain("<gradient:#fff:#000>x</gradient>").contains("<"));
    }
}
