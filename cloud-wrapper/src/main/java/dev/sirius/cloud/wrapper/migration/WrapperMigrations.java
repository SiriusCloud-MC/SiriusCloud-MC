package dev.sirius.cloud.wrapper.migration;

import dev.sirius.cloud.driver.migration.Migration;

import java.util.List;

/**
 * Every wrapper migration, in order. None yet: everything the wrapper's files
 * have gained so far has had a default. The list exists so that the first
 * change that needs one has somewhere to go, and so wrappers already record
 * their data version from now on.
 */
public final class WrapperMigrations {

    private WrapperMigrations() {
    }

    public static List<Migration> all() {
        return List.of();
    }
}
