package com.promptvidya.trustdesk.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Supply-chain and runtime hardening as tests: the resolved jars match
 * the committed lock digest for digest; the lock itself is well
 * formed; a tampered jar would be reported by path, never by content;
 * and the runtime flags in the image leave no debug or management
 * port open.
 */
class SupplyChainTest {

    private static final Path DOCKERFILE = Path.of("deploy", "Dockerfile");

    @Test
    void everyResolvedJarMatchesTheCommittedLock() {
        var actual = DependencyLock.current();
        if (Boolean.getBoolean("trustdesk.dependency-lock.update")) {
            DependencyLock.write(DependencyLock.LOCK_FILE, actual);
        }

        var locked = DependencyLock.read(DependencyLock.LOCK_FILE);

        assertThat(actual).hasSizeGreaterThan(50);
        assertThat(DependencyLock.differences(locked, actual))
                .as("dependency drift — regenerate the lock and review the diff")
                .isEmpty();
    }

    @Test
    void theLockIsWellFormedAndCoversTheFrameworksThisCourseDependsOn() {
        var locked = DependencyLock.read(DependencyLock.LOCK_FILE);

        assertThat(locked.keySet()).anyMatch(path -> path.startsWith("org/springframework/ai/"))
                .anyMatch(path -> path.startsWith("org/springframework/security/"))
                .anyMatch(path -> path.startsWith("io/modelcontextprotocol/"));
        assertThat(locked.values()).allSatisfy(digest -> assertThat(digest).matches("[0-9a-f]{64}"));
    }

    @Test
    void driftIsReportedByPathNeverByContent() {
        var locked = new TreeMap<String, String>();
        locked.put("org/example/kept/1.0/kept-1.0.jar", "a".repeat(64));
        locked.put("org/example/gone/1.0/gone-1.0.jar", "b".repeat(64));
        var actual = new TreeMap<String, String>();
        actual.put("org/example/kept/1.0/kept-1.0.jar", "c".repeat(64));
        actual.put("org/example/new/2.0/new-2.0.jar", "d".repeat(64));

        assertThat(DependencyLock.differences(locked, actual)).containsExactly(
                "changed: org/example/kept/1.0/kept-1.0.jar",
                "added: org/example/new/2.0/new-2.0.jar",
                "removed: org/example/gone/1.0/gone-1.0.jar");
    }

    @Test
    void theRuntimeFlagsOpenNoDebugOrManagementPort() throws IOException {
        var dockerfile = Files.readString(DOCKERFILE);
        var options = dockerfile.lines().filter(line -> line.contains("JAVA_TOOL_OPTIONS")).findFirst().orElseThrow();

        assertThat(options).contains("-XX:+ExitOnOutOfMemoryError").contains("-XX:MaxRAMPercentage")
                .doesNotContain("jdwp").doesNotContain("jmxremote").doesNotContain("-Xdebug");
        assertThat(dockerfile).doesNotContain("EXPOSE 5005").doesNotContain("EXPOSE 9010");
    }
}
