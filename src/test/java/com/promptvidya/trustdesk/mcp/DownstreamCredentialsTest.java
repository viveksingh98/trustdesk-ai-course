package com.promptvidya.trustdesk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.SignedJWT;
import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import com.promptvidya.trustdesk.security.McpDoor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;

/**
 * No token passthrough, proven: the downstream token is a different
 * token — downstream audience, person as subject, this server as actor,
 * narrowed scope — and without an inbound token there is nothing to
 * act for.
 */
@SpringBootTest
class DownstreamCredentialsTest {

    @Autowired
    private DownstreamCredentials credentials;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private DevelopmentJwtKeys keys;

    @AfterEach
    void clearPrincipal() {
        TestSecurityContextHolder.clearContext();
    }

    private String inboundFor(String scope) throws Exception {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(McpDoor.MCP_AUDIENCE))
                .subject("alice")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .claim("scope", scope)
                .build();
        var token = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        var jwt = McpDoor.mcpDecoder(keys).decode(token);
        TestSecurityContextHolder.setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("policies:it-hardware"))));
        return token;
    }

    @Test
    void theDownstreamTokenIsNotTheInboundTokenAndNamesThisServerAsActor() throws Exception {
        var inbound = inboundFor("agent:chat policies:it-hardware");

        var outbound = credentials.forAudience("payroll-api", Set.of("policies:it-hardware", "payroll:export"));

        assertThat(outbound).isNotEqualTo(inbound);
        var claims = SignedJWT.parse(outbound).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("payroll-api");
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.getStringClaim("scope")).isEqualTo("policies:it-hardware");
        assertThat(claims.getJSONObjectClaim("act")).containsEntry("sub", DownstreamCredentials.SELF);
    }

    @Test
    void nothingSharedMeansNoDownstreamToken() throws Exception {
        inboundFor("agent:chat");

        assertThatThrownBy(() -> credentials.forAudience("payroll-api", Set.of("payroll:export")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void withoutAnInboundTokenThereIsNothingToActFor() {
        assertThatThrownBy(() -> credentials.forAudience("payroll-api", Set.of("policies:it-hardware")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> credentials.forAudience(DownstreamCredentials.SELF, Set.of("policies:it-hardware")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
