package dev.sirius.cloud.driver.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A SiriusCloud version like {@code 1.0.4} or {@code 1.1.0-SNAPSHOT}.
 *
 * <p>Found anywhere in a string, so a release tagged {@code v1.0.4} or titled
 * "SiriusCloud 1.0.4" reads the same. A version with a suffix is older than
 * the same numbers without one: {@code 1.0.4-SNAPSHOT} is on its way to
 * {@code 1.0.4}, not past it.
 */
public record Version(List<Integer> numbers, String suffix) implements Comparable<Version> {

    private static final Pattern PATTERN = Pattern.compile("(\\d+(?:\\.\\d+){1,3})(-[0-9A-Za-z.]+)?");

    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        List<Integer> numbers = new ArrayList<>();
        for (String part : matcher.group(1).split("\\.")) {
            numbers.add(Integer.parseInt(part));
        }
        String suffix = matcher.group(2) == null ? "" : matcher.group(2).substring(1);
        return Optional.of(new Version(List.copyOf(numbers), suffix));
    }

    public boolean isPreRelease() {
        return !suffix.isEmpty();
    }

    @Override
    public int compareTo(Version other) {
        int length = Math.max(numbers.size(), other.numbers.size());
        for (int index = 0; index < length; index++) {
            int mine = index < numbers.size() ? numbers.get(index) : 0;
            int theirs = index < other.numbers.size() ? other.numbers.get(index) : 0;
            if (mine != theirs) {
                return Integer.compare(mine, theirs);
            }
        }
        if (suffix.isEmpty() != other.suffix.isEmpty()) {
            return suffix.isEmpty() ? 1 : -1;
        }
        return suffix.compareTo(other.suffix);
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < numbers.size(); index++) {
            text.append(index == 0 ? "" : ".").append(numbers.get(index));
        }
        return suffix.isEmpty() ? text.toString() : text + "-" + suffix;
    }
}
