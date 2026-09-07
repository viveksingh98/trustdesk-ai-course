package com.promptvidya.trustdesk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import com.promptvidya.trustdesk.security.McpDoor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * The MCP door as a stranger meets it: a challenge that points at the
 * metadata, a metadata document anyone may read, a token for the agent
 * API refused on audience, and a token for this server admitted.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class McpDoorTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder encoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base() {
        return "http://localhost:" + port;
    }

    private String tokenFor(String audience) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(audience))
                .subject("alice")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .claim("scope", "agent:chat policies:it-hardware")
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anonymousCallersGetAChallengeThatPointsAtTheMetadata() throws Exception {
        var response = get(McpDoor.MCP_ENDPOINT, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse(""))
                .startsWith("Bearer")
                .contains("resource_metadata=\"" + base() + McpDoor.METADATA_PATH + "\"");
    }

    @Test
    void theMetadataDocumentIsPublicAndNamesTheResourceAndItsIssuer() throws Exception {
        var response = get(McpDoor.METADATA_PATH, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"resource\":\"" + McpDoor.RESOURCE + "\"")
                .contains(DevelopmentJwtKeys.ISSUER)
                .contains("\"bearer_methods_supported\":[\"header\"]");
    }

    @Test
    void aTokenForTheAgentApiDoesNotOpenTheMcpServer() throws Exception {
        var response = get(McpDoor.MCP_ENDPOINT, tokenFor(DevelopmentJwtKeys.AUDIENCE));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).contains("invalid_token");
        assertThatThrownBy(() -> RemoteToolBelt.connect(
                        base(), McpDoor.MCP_ENDPOINT, tokenFor(DevelopmentJwtKeys.AUDIENCE), Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aTokenMintedForThisServerIsAdmitted() {
        try (var belt = RemoteToolBelt.connect(
                base(), McpDoor.MCP_ENDPOINT, tokenFor(McpDoor.MCP_AUDIENCE), Duration.ofSeconds(10))) {
            assertThat(belt.advertisedTools()).hasSize(2);
        }
    }
}
