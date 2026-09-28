package dev.sirius.cloud.node.cluster;

import dev.sirius.cloud.driver.sync.ContentIndex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The files a cluster keeps identical on every node, and their content hashes.
 *
 * <p>What is replicated is the network's data: groups, templates, each
 * module's folder, the JSON database, the key-value store and the data
 * version. Jars are not, except inside templates: everywhere else they are
 * this node's program, and a leader on an older build pushing its modules
 * onto an updated node would quietly downgrade it. In a template a jar is a
 * plugin for the servers, which is content like any other. Nothing under
 * {@code local/} that describes this machine alone is replicated either.
 */
public final class FileIndex {

    /** Directories and files, relative to the node directory, whose contents are replicated. */
    static final List<String> ROOTS = List.of(
            "groups", "templates", "modules", "local/database", "local/store.json", "local/data-version.json");

    private final ContentIndex index;

    public FileIndex(Path root) {
        this.index = new ContentIndex(root, ROOTS, FileIndex::replicated);
    }

    /** Whether a path is one this index manages - the only paths a follower will write or delete. */
    public static boolean replicated(String relative) {
        String name = relative.substring(relative.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        boolean template = relative.startsWith("templates/");
        if ((name.endsWith(".jar") && !template) || name.endsWith(".tmp") || name.endsWith(".part")
                || name.equals(".lock") || relative.contains("..") || relative.startsWith("/")) {
            return false;
        }
        return ROOTS.stream().anyMatch(prefix -> relative.equals(prefix) || relative.startsWith(prefix + "/"));
    }

    public Map<String, String> manifest() throws IOException {
        return index.manifest();
    }

    public byte[] read(String relative) throws IOException {
        return index.read(relative);
    }

    public void write(String relative, byte[] content) throws IOException {
        index.write(relative, content);
    }

    public void append(String relative, byte[] chunk, boolean first) throws IOException {
        index.append(relative, chunk, first);
    }

    public void finish(String relative) throws IOException {
        index.finish(relative);
    }

    public void delete(String relative) throws IOException {
        index.delete(relative);
    }

    static String hash(byte[] content) {
        return ContentIndex.hash(content);
    }
}
