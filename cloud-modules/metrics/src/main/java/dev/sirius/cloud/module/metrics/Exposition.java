package dev.sirius.cloud.module.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the Prometheus text format, version 0.0.4.
 *
 * <p>Small enough that pulling in a client library would be most of the jar.
 * Samples are grouped under their metric's HELP and TYPE lines however they
 * are added, which the format requires.
 */
final class Exposition {

    private record Family(String help, String type, StringBuilder samples) {
    }

    private final Map<String, Family> families = new LinkedHashMap<>();

    Exposition gauge(String name, String help, double value, String... labels) {
        return sample(name, help, "gauge", value, labels);
    }

    Exposition counter(String name, String help, double value, String... labels) {
        return sample(name, help, "counter", value, labels);
    }

    private Exposition sample(String name, String help, String type, double value, String... labels) {
        if (labels.length % 2 != 0) {
            throw new IllegalArgumentException("labels come in name/value pairs");
        }
        Family family = families.computeIfAbsent(name, key -> new Family(help, type, new StringBuilder()));
        StringBuilder line = family.samples().append(name);
        if (labels.length > 0) {
            line.append('{');
            for (int index = 0; index < labels.length; index += 2) {
                if (index > 0) {
                    line.append(',');
                }
                line.append(labels[index]).append("=\"").append(escape(labels[index + 1])).append('"');
            }
            line.append('}');
        }
        line.append(' ').append(format(value)).append('\n');
        return this;
    }

    String render() {
        StringBuilder out = new StringBuilder();
        families.forEach((name, family) -> {
            out.append("# HELP ").append(name).append(' ').append(family.help()).append('\n');
            out.append("# TYPE ").append(name).append(' ').append(family.type()).append('\n');
            out.append(family.samples());
        });
        return out.toString();
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static String format(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "+Inf" : "-Inf";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
