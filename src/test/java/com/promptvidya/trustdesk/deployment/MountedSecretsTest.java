package com.promptvidya.trustdesk.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A mounted secrets directory, offline: owner-only files become named
 * secrets with trailing newlines removed; a world-readable file, a
 * strange name, or an oversized file refuses the whole directory; and
 * nothing about the directory ever prints a secret.
 */
class MountedSecretsTest {

    @TempDir
    Path mount;

    private Path secret(String name, String value, String permissions) throws IOException {
        var file = mount.resolve(name);
        Files.writeString(file, value, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }

    @Test
    void ownerOnlyFilesBecomeNamedSecretsWithoutTheirTrailingNewline() throws IOException {
        secret("openai-api-key", "sk-live-example\n", "rw-------");
        secret("jwt-private-key", "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n", "r--------");

        var secrets = MountedSecrets.read(mount);

        assertThat(secrets.names()).containsExactlyInAnyOrder("openai-api-key", "jwt-private-key");
        assertThat(secrets.require("openai-api-key")).isEqualTo("sk-live-example");
        assertThat(secrets.text("jwt-private-key")).get().asString().startsWith("-----BEGIN PRIVATE KEY-----").doesNotEndWith("\n");
        assertThat(secrets.text("absent")).isEmpty();
    }

    @Test
    void aFileOtherUsersCanReadRefusesTheWholeDirectory() throws IOException {
        secret("openai-api-key", "sk-live-example", "rw-------");
        secret("db-password", "hunter2", "rw-r--r--");

        assertThatThrownBy(() -> MountedSecrets.read(mount))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readable by other users: db-password")
                .hasMessageNotContaining("hunter2");
    }

    @Test
    void strangeNamesAndOversizedFilesAreRefused() throws IOException {
        secret("openai-api-key", "sk-live-example", "rw-------");
        secret("Key With Spaces", "x", "rw-------");
        assertThatThrownBy(() -> MountedSecrets.read(mount)).isInstanceOf(IllegalStateException.class).hasMessageContaining("plain identifiers");

        Files.delete(mount.resolve("Key With Spaces"));
        secret("huge", "x".repeat(MountedSecrets.MAXIMUM_SECRET_BYTES + 1), "rw-------");
        assertThatThrownBy(() -> MountedSecrets.read(mount)).isInstanceOf(IllegalStateException.class).hasMessageContaining("too large: huge");
    }

    @Test
    void missingRequiredSecretsFailByNameAndNothingPrintsAValue() throws IOException {
        secret("openai-api-key", "sk-live-example", "rw-------");
        var secrets = MountedSecrets.read(mount);

        assertThatThrownBy(() -> secrets.require("jwt-private-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("required secret is missing: jwt-private-key");
        assertThat(secrets.toString()).isEqualTo("MountedSecrets[openai-api-key]").doesNotContain("sk-live");
        assertThatThrownBy(() -> MountedSecrets.read(mount.resolve("nowhere")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing: nowhere");
    }
}
