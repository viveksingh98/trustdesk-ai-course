package com.promptvidya.trustdesk.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * The supply chain as a test fixture. Every jar the build resolved is
 * listed with its SHA-256 in a lock file committed beside the
 * deployment descriptors; the suite recomputes the digests from the
 * running classpath and fails on any jar that is new, missing, or
 * changed. A dependency bump therefore arrives as a reviewed diff of
 * the lock file, never as a silent change to what ships.
 */
public final class DependencyLock {

    public static final Path LOCK_FILE = Path.of("deploy", "dependency-lock.txt");
    static final String REPOSITORY_MARKER = "/repository/";

    private DependencyLock() {}

    /** Coordinates-ish path under the repository → digest, for every jar on this classpath. */
    public static TreeMap<String, String> current() {
        var entries = new TreeMap<String, String>();
        for (var element : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            var marker = element.indexOf(REPOSITORY_MARKER);
            if (marker < 0 || !element.endsWith(".jar")) {
                continue;
            }
            entries.put(element.substring(marker + REPOSITORY_MARKER.length()), digest(Path.of(element)));
        }
        return entries;
    }

    public static TreeMap<String, String> read(Path lockFile) {
        var entries = new TreeMap<String, String>();
        try {
            for (var line : Files.readAllLines(lockFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var parts = line.split("  ", 2);
                if (parts.length != 2) {
                    throw new IllegalStateException("lock line is '<sha256>  <path>': " + line);
                }
                entries.put(parts[1], parts[0]);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return entries;
    }

    public static void write(Path lockFile, TreeMap<String, String> entries) {
        var lines = new java.util.ArrayList<String>();
        lines.add("# TrustDesk dependency lock: sha256 and repository path of every resolved jar.");
        lines.add("# Regenerate with -Dtrustdesk.dependency-lock.update=true and review the diff.");
        entries.forEach((path, digest) -> lines.add(digest + "  " + path));
        try {
            Files.createDirectories(lockFile.getParent());
            Files.write(lockFile, lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Human-readable differences: added, removed, and changed jars, by path only. */
    public static List<String> differences(TreeMap<String, String> locked, TreeMap<String, String> actual) {
        var differences = new java.util.ArrayList<String>();
        actual.forEach((path, digest) -> {
            var expected = locked.get(path);
            if (expected == null) {
                differences.add("added: " + path);
            } else if (!expected.equals(digest)) {
                differences.add("changed: " + path);
            }
        });
        locked.keySet().stream().filter(path -> !actual.containsKey(path)).forEach(path -> differences.add("removed: " + path));
        return List.copyOf(differences);
    }

    static String digest(Path jar) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is mandatory on the JVM", exception);
        }
    }
}
