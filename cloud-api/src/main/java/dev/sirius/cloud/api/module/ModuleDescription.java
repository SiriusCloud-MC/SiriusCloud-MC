package dev.sirius.cloud.api.module;

import java.util.List;

/**
 * A module's {@code module.json}.
 *
 * <p>A plain POJO with no parsing of its own, because {@code cloud-api} has no
 * dependencies and is not about to gain a JSON library — the node reads it and
 * hands the result over.
 */
public final class ModuleDescription {

    private String id;
    private String version;

    /** Fully-qualified class implementing {@link CloudModule}. */
    private String main;

    /**
     * The {@code cloud-api} version this module was built against.
     *
     * <p>Recorded rather than enforced for now: with the API still moving, a
     * refusal to load would be noise. It is here so that enforcing it later
     * does not need every module rebuilt to gain the field.
     */
    private String apiVersion;

    private String description;
    private List<String> authors;

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    ModuleDescription() {
    }

    public ModuleDescription(String id, String version, String main) {
        this.id = id;
        this.version = version;
        this.main = main;
    }

    public String id() {
        return id;
    }

    public String version() {
        return version == null || version.isBlank() ? "unknown" : version;
    }

    public String main() {
        return main;
    }

    public String apiVersion() {
        return apiVersion == null ? "" : apiVersion;
    }

    public String description() {
        return description == null ? "" : description;
    }

    public List<String> authors() {
        return authors == null ? List.of() : authors;
    }

    /** Whether the fields the loader cannot work without are present. */
    public boolean isValid() {
        return id != null && !id.isBlank() && main != null && !main.isBlank();
    }

    @Override
    public String toString() {
        return id + " v" + version();
    }
}
