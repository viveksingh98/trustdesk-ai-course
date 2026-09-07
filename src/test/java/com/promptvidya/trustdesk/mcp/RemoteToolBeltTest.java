package com.promptvidya.trustdesk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import com.promptvidya.trustdesk.security.McpDoor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * A real MCP round trip over streamable HTTP on a random port: the
 * client initializes with a bearer token, sees what the server
 * advertises, calls through the fixed belt, gets fenced data back, and
 * is refused — by the door with no token, by the tool for a foreign
 * ticket, by the belt for a tool we never allowed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RemoteToolBeltTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder encoder;

    private String bearerFor(String subject, String scope) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(McpDoor.MCP_AUDIENCE))
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .claim("scope", scope)
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private RemoteToolBelt connectAs(String subject, String scope) {
        return RemoteToolBelt.connect(
                "http://localhost:" + port, "/mcp", bearerFor(subject, scope), Duration.ofSeconds(10));
    }

    @Test
    void theClientInitializesAndSeesExactlyTheAdvertisedTools() {
        try (var belt = connectAs("alice", "agent:chat policies:it-hardware")) {
            assertThat(belt.advertisedTools()).containsExactly("policy_article", "ticket_by_id");
            assertThat(belt.asToolCallbacks().getToolCallbacks())
                    .extracting(callback -> callback.getToolDefinition().name())
                    .containsExactlyInAnyOrder("policy_article", "ticket_by_id");
        }
    }

    @Test
    void resultsArriveFencedAsUntrustedData() {
        try (var belt = connectAs("alice", "agent:chat policies:it-hardware")) {
            var result = belt.call("ticket_by_id", Map.of("ticketId", "T-1"));

            assertThat(result)
                    .startsWith("<tool_result source=\"trustdesk-mcp\" tool=\"ticket_by_id\" trust=\"untrusted-data\">")
                    .contains("VPN")
                    .endsWith("</tool_result>");
        }
    }

    @Test
    void theServerStillEnforcesOwnershipAcrossTheHop() {
        try (var belt = connectAs("alice", "agent:chat policies:it-hardware")) {
            assertThatThrownBy(() -> belt.call("ticket_by_id", Map.of("ticketId", "T-2")))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void theBeltIsFixedOnTheClientSideToo() {
        try (var belt = connectAs("alice", "agent:chat")) {
            assertThatThrownBy(() -> belt.call("delete_everything", Map.of()))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("not on the belt");
        }
    }

    @Test
    void noTokenMeansInitializationFailsAtTheDoor() {
        assertThatThrownBy(() -> RemoteToolBelt.connect(
                        "http://localhost:" + port, "/mcp", "not-a-token", Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);
    }
}
