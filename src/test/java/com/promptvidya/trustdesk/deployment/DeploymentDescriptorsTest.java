package com.promptvidya.trustdesk.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the deployment descriptors: the image runs as an
 * unprivileged user from extracted layers with a health check, and the
 * compose file keeps the agent read-only, capability-free, unexposed,
 * behind an edge proxy, on an internal network with an egress proxy as
 * its only way out, and fed by file-mounted secrets — never by
 * environment variables carrying values.
 */
class DeploymentDescriptorsTest {

    private static final Path DOCKERFILE = Path.of("deploy", "Dockerfile");
    private static final Path COMPOSE = Path.of("deploy", "compose.yaml");

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    /** The lines of one compose service, from its name to the next top-level or service key. */
    private static String service(String compose, String name) {
        var matcher = Pattern.compile("(?ms)^  " + name + ":\\n(.*?)(?=^  [a-z-]+:\\n|^[a-z]+:\\n|\\z)").matcher(compose);
        assertThat(matcher.find()).as("service %s", name).isTrue();
        return matcher.group(1);
    }

    @Test
    void theImageIsMultiStageUnprivilegedLayeredAndHealthChecked() throws IOException {
        var dockerfile = read(DOCKERFILE);
        var stages = dockerfile.lines().filter(line -> line.startsWith("FROM ")).toList();
        var lastStage = dockerfile.substring(dockerfile.lastIndexOf("\nFROM "));

        assertThat(stages).hasSizeGreaterThanOrEqualTo(3);
        assertThat(lastStage).contains("USER trustdesk").contains("HEALTHCHECK")
                .contains("org.springframework.boot.loader.launch.JarLauncher")
                .contains("SPRING_PROFILES_ACTIVE=prod")
                .doesNotContain("USER root");
        assertThat(dockerfile).contains("extract --layers").doesNotContainPattern("(?m)^ADD ")
                .doesNotContainIgnoringCase("curl ").doesNotContain("| sh");
        assertThat(List.of("dependencies", "spring-boot-loader", "snapshot-dependencies", "application"))
                .allSatisfy(layer -> assertThat(lastStage).contains("/layers/" + layer + "/"));
    }

    @Test
    void theAgentContainerIsReadOnlyCapabilityFreeAndUnexposed() throws IOException {
        var agent = service(read(COMPOSE), "trustdesk");

        assertThat(agent).contains("read_only: true").contains("cap_drop:").contains("- ALL")
                .contains("no-new-privileges:true").contains("healthcheck:").contains("memory: 1g")
                .doesNotContain("ports:").doesNotContain("privileged");
    }

    @Test
    void secretsArriveAsFilesNeverAsEnvironmentValues() throws IOException {
        var compose = read(COMPOSE);
        var agent = service(compose, "trustdesk");

        assertThat(agent).contains("- openai-api-key").contains("- jwt-private-key").contains("- jwt-public-key")
                .contains("TRUSTDESK_SECRETS_DIRECTORY: /run/secrets")
                .doesNotContainPattern("(?i)OPENAI_API_KEY:")
                .doesNotContainPattern("(?i)(password|secret|token)\\s*:\\s*\\S");
        assertThat(compose).containsPattern("(?ms)^secrets:\\n.*openai-api-key:\\n\\s+file: ");
    }

    @Test
    void theOnlyWayInIsTheEdgeAndTheOnlyWayOutIsTheEgressProxy() throws IOException {
        var compose = read(COMPOSE);
        var agent = service(compose, "trustdesk");
        var edge = service(compose, "edge");
        var egress = service(compose, "egress");

        assertThat(compose).containsPattern("(?ms)^networks:\\n.*  internal:\\n\\s+internal: true");
        assertThat(agent).contains("- internal").doesNotContain("- edge").doesNotContain("- outbound")
                .contains("HTTPS_PROXY: http://egress:3128");
        assertThat(edge).contains("- \"443:443\"").contains("- edge").contains("- internal");
        assertThat(egress).contains("- internal").contains("- outbound").doesNotContain("ports:");
    }
}
