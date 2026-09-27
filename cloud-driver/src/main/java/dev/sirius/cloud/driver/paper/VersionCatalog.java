package dev.sirius.cloud.driver.paper;

import java.io.IOException;
import java.util.List;

/**
 * The versions one server platform publishes, oldest first.
 *
 * <p>Every implementation normalises to oldest-first however its source
 * orders things - and they disagree: PaperMC groups newest-first at two levels,
 * Purpur lists oldest-first, Fabric newest-first. Getting that wrong once
 * already resolved {@code latest} to the oldest release of a family, so the
 * normalisation lives in each catalog and everything downstream trusts it.
 */
public interface VersionCatalog {

    String project();

    List<String> versions() throws IOException;

    /** The newest stable release. */
    String latest() throws IOException;

    /** The newest release of any kind, pre-releases included. */
    String newestPublished() throws IOException;

    /** Resolves {@code latest} and validates anything else. */
    String resolve(String version) throws IOException;

    /** Every version from {@code first} onward, by position rather than by comparing strings. */
    List<String> from(String first) throws IOException;
}
