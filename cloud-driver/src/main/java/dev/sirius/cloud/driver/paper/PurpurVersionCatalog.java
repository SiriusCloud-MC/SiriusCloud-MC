package dev.sirius.cloud.driver.paper;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Purpur's versions. Its API lists them oldest first already. */
public final class PurpurVersionCatalog extends SimpleVersionCatalog {

    public static final String API = "https://api.purpurmc.org/v2/purpur";

    public PurpurVersionCatalog() {
        super("purpur");
    }

    @Override
    protected List<String> fetch() throws IOException, InterruptedException {
        List<String> versions = new ArrayList<>();
        for (JsonElement element : getJson(API).getAsJsonObject().getAsJsonArray("versions")) {
            versions.add(element.getAsString());
        }
        return versions;
    }
}
