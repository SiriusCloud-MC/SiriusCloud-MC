package dev.sirius.cloud.node.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.sirius.cloud.driver.migration.Migration;
import dev.sirius.cloud.driver.migration.MigrationContext;

import java.io.IOException;
import java.util.Optional;

/**
 * The starter admin prefix changed from {@code [Admin] Name} to
 * {@code Admin | Name} when ranks came to chat and nametags.
 *
 * <p>Only the untouched old default is replaced. A prefix somebody chose is
 * theirs, even if it happens to be on the group called admin.
 */
final class AdminPrefixMigration implements Migration {

    static final String GROUPS = "modules/permissions/groups.json";
    static final String OLD_PREFIX = "&c[Admin] ";
    static final String NEW_PREFIX = "&cAdmin &8| &c";

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String description() {
        return "Give the starter admin group its new 'Admin | Name' prefix";
    }

    @Override
    public void apply(MigrationContext context) throws IOException {
        Optional<JsonElement> file = context.readJson(GROUPS);
        if (file.isEmpty() || !file.get().isJsonArray()) {
            return;
        }
        boolean changed = false;
        for (JsonElement element : file.get().getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject group = element.getAsJsonObject();
            if (group.has("name") && group.get("name").getAsString().equalsIgnoreCase("admin")
                    && group.has("prefix") && OLD_PREFIX.equals(group.get("prefix").getAsString())) {
                group.addProperty("prefix", NEW_PREFIX);
                context.note("admin: prefix '" + OLD_PREFIX + "' -> '" + NEW_PREFIX + "'");
                changed = true;
            }
        }
        if (changed) {
            context.writeJson(GROUPS, file.get());
        }
    }
}
