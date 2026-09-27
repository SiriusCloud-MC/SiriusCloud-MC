package dev.sirius.cloud.node.migration;

import dev.sirius.cloud.driver.migration.Migration;

import java.util.List;

/**
 * Every node migration, in order. Append new ones; never renumber or remove
 * one that has shipped (see {@link Migration}).
 */
public final class NodeMigrations {

    private NodeMigrations() {
    }

    public static List<Migration> all() {
        return List.of(
                new AdminPrefixMigration());
    }
}
