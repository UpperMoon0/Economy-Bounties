package com.nstut.economybounties.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Version-neutral namespaced identifier used by the public API.
 */
public record NamespacedId(String namespace, String path) implements Comparable<NamespacedId> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    public NamespacedId {
        namespace = Objects.requireNonNull(namespace, "namespace");
        path = Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
    }

    public static NamespacedId parse(String value) {
        Objects.requireNonNull(value, "value");
        int split = value.indexOf(':');
        if (split <= 0 || split == value.length() - 1 || value.indexOf(':', split + 1) >= 0) {
            throw new IllegalArgumentException("Expected namespaced id namespace:path, got: " + value);
        }
        return new NamespacedId(value.substring(0, split), value.substring(split + 1));
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }

    @Override
    public int compareTo(NamespacedId other) {
        return toString().compareTo(other.toString());
    }
}
