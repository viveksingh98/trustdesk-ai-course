package com.promptvidya.trustdesk.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Secrets arrive as files, never as text in configuration: a vault
 * agent, a Kubernetes secret, or a systemd credential mounts one file
 * per secret into a directory that only the service user can read.
 * This reader turns that directory into a map, refusing files any
 * other user could read and names that are not plain identifiers, and
 * it never logs or returns the directory's contents in a message.
 */
public final class MountedSecrets {

    static final int MAXIMUM_SECRET_BYTES = 64 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Set<PosixFilePermission> OTHERS_MAY_READ = Set.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);

    private final Map<String, byte[]> secrets;

    private MountedSecrets(Map<String, byte[]> secrets) {
        this.secrets = secrets;
    }

    /** Read every regular file in the directory as one secret named after the file. */
    public static MountedSecrets read(Path directory) {
        Objects.requireNonNull(directory);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("secrets directory is missing: " + directory.getFileName());
        }
        var secrets = new TreeMap<String, byte[]>();
        try (Stream<Path> entries = Files.list(directory)) {
            for (var entry : entries.filter(Files::isRegularFile).toList()) {
                var name = entry.getFileName().toString();
                if (!NAME.matcher(name).matches()) {
                    throw new IllegalStateException("secret names are plain identifiers: rejected one file");
                }
                if (Files.size(entry) > MAXIMUM_SECRET_BYTES) {
                    throw new IllegalStateException("secret too large: " + name);
                }
                var permissions = Files.getPosixFilePermissions(entry);
                if (permissions.stream().anyMatch(OTHERS_MAY_READ::contains)) {
                    throw new IllegalStateException("secret readable by other users: " + name);
                }
                secrets.put(name, Files.readAllBytes(entry));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return new MountedSecrets(secrets);
    }

    public Set<String> names() {
        return Set.copyOf(secrets.keySet());
    }

    /** A secret as text with surrounding whitespace removed — the trailing newline every editor adds. */
    public Optional<String> text(String name) {
        return Optional.ofNullable(secrets.get(name))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8).strip());
    }

    public String require(String name) {
        return text(name).filter(value -> !value.isEmpty())
                .orElseThrow(() -> new IllegalStateException("required secret is missing: " + name));
    }

    /** Never the contents — only which names are present. */
    @Override
    public String toString() {
        return "MountedSecrets" + names();
    }
}
