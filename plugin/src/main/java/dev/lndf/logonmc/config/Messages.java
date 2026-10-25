package dev.lndf.logonmc.config;

import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * messages.yml, rendered with MiniMessage. Keys are dotted paths such as {@code auth.logged-in}.
 * Every message can use {@code <prefix>}.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, String> raw = new HashMap<>();
    private final TagResolver prefix;

    public Messages(ConfigurationNode root, ConfigurationNode defaults) {
        flatten("", defaults);
        flatten("", root);
        this.prefix = Placeholder.parsed("prefix", raw.getOrDefault("prefix", ""));
    }

    private void flatten(String path, ConfigurationNode node) {
        if (node.isMap()) {
            node.childrenMap().forEach((key, child) ->
                    flatten(path.isEmpty() ? key.toString() : path + "." + key, child));
        } else if (node.getString() != null) {
            raw.put(path, node.getString());
        }
    }

    public Component get(String key, TagResolver... placeholders) {
        String text = raw.get(key);
        if (text == null) {
            return Component.text(key);
        }
        return MINI.deserialize(text, TagResolver.builder().resolver(prefix).resolvers(placeholders).build());
    }

    public static TagResolver arg(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }
}
