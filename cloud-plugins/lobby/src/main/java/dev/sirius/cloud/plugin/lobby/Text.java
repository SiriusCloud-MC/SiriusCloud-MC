package dev.sirius.cloud.plugin.lobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MiniMessage with {@code {placeholders}}.
 *
 * <p>Placeholder values are inserted as text or components, never parsed, so
 * a player called {@code <red>} or a server name with tags in it stays exactly
 * what it is.
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component render(String template, Map<String, ?> values) {
        String prepared = template;
        List<TagResolver> resolvers = new ArrayList<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String tag = "sc_" + entry.getKey().replace('-', '_');
            prepared = prepared.replace("{" + entry.getKey() + "}", "<" + tag + ">");
            Object value = entry.getValue();
            resolvers.add(value instanceof Component component
                    ? Placeholder.component(tag, component)
                    : Placeholder.unparsed(tag, String.valueOf(value)));
        }
        return MINI.deserialize(prepared, TagResolver.resolver(resolvers));
    }

    public static Component render(String template) {
        return render(template, Map.of());
    }

    /** For item names and lore: without this, Minecraft shows them in italics. */
    public static Component item(String template, Map<String, ?> values) {
        return render(template, values).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component item(String template) {
        return item(template, Map.of());
    }
}
