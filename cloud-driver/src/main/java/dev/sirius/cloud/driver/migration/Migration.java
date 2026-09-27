package dev.sirius.cloud.driver.migration;

import java.io.IOException;

/**
 * One step that brings files written by an older SiriusCloud up to date.
 *
 * <p>Versions are a plain counter, separate from the release version: each
 * migration takes the next number, and an install records the highest one it
 * has had. Never renumber or remove one that has shipped, since installs out
 * there have recorded it; a migration that turns out wrong is fixed by a new
 * one after it.
 *
 * <p>Write migrations to be safe to run on data they do not recognise: leave
 * anything that is not exactly the old shape alone, because an operator may
 * already have changed it by hand.
 */
public interface Migration {

    /** This migration's number. Unique, and higher than every one before it. */
    int version();

    /** One line for the log and the history, e.g. "Give the admin group its new prefix". */
    String description();

    void apply(MigrationContext context) throws IOException;
}
