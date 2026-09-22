package dev.sirius.cloud.module.rest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small JSON helpers.
 *
 * <p>Responses are built as explicit maps rather than by serialising
 * {@code ServiceInfo} and friends directly. Those are internal shapes with
 * derived accessors and no stable field contract; reflecting over them would
 * make every future field rename a breaking API change, and would leak things
 * like a group's Java path to anyone holding a token.
 */
final class Json {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    /** An insertion-ordered map, so responses read the same way every time. */
    static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> body = map();
        body.put("error", message);
        return body;
    }
}
