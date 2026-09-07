package com.promptvidya.trustdesk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import com.promptvidya.trustdesk.security.McpDoor;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;

/**
 * The confused deputy, MCP edition. A naive bridge forwards the token
 * it received to a downstream payroll service. Against a naive
 * downstream the forwarded token is accepted with every scope the
 * person had and no trace of the bridge. Against a strict downstream
 * it is refused on audience. DownstreamCredentials makes the strict
 * downstream accept — with the bridge visible as the actor.
 */
@SpringBootTest
class NaiveBridgeAttackTest {

    static final String PAYROLL_API = "payroll-api";

    /** The bug, in one line: whatever came in goes out. */
    static final class NaiveBridge {
        String forward(String inboundToken) {
            return inboundToken;
        }
    }

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private DevelopmentJwtKeys keys;

    @Autowired
    private DownstreamCredentials credentials;

    @AfterEach
    void clearPrincipal() {
        TestSecurityContextHolder.clearContext();
    }

    /** A person's MCP token, over-scoped the way real tokens often are. */
    private String inboundMcpToken() throws Exception {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(McpDoor.MCP_AUDIENCE))
                .subject("alice")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .claim("scope", "agent:chat policies:it-hardware payroll:export")
                .build();
        var token = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        var jwt = McpDoor.mcpDecoder(keys).decode(token);
        TestSecurityContextHolder.setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("payroll:export"))));
        return token;
    }

    private JwtDecoder naiveDownstream() throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(DevelopmentJwtKeys.ISSUER));
        return decoder;
    }

    private JwtDecoder strictDownstream() throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(DevelopmentJwtKeys.ISSUER),
                new JwtClaimValidator<Collection<String>>(
                        JwtClaimNames.AUD, audience -> audience != null && audience.contains(PAYROLL_API))));
        return decoder;
    }

    @Test
    void theNaiveBridgeHandsPayrollThePersonsWholeTokenWithNoTraceOfItself() throws Exception {
        var forwarded = new NaiveBridge().forward(inboundMcpToken());

        Jwt seenByPayroll = naiveDownstream().decode(forwarded);

        assertThat(seenByPayroll.getAudience()).containsExactly(McpDoor.MCP_AUDIENCE);
        assertThat(seenByPayroll.getClaimAsString("scope")).contains("payroll:export").contains("agent:chat");
        assertThat(DelegatedTokensView.actingAgent(seenByPayroll)).isEmpty();
    }

    @Test
    void aStrictDownstreamRefusesTheForwardedTokenOnAudienceAlone() throws Exception {
        var forwarded = new NaiveBridge().forward(inboundMcpToken());

        assertThatThrownBy(() -> strictDownstream().decode(forwarded))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("aud");
    }

    @Test
    void downstreamCredentialsSatisfyTheStrictDownstreamAndNameTheBridge() throws Exception {
        inboundMcpToken();

        var minted = credentials.forAudience(PAYROLL_API, Set.of("payroll:export"));
        Jwt seenByPayroll = strictDownstream().decode(minted);

        assertThat(seenByPayroll.getSubject()).isEqualTo("alice");
        assertThat(seenByPayroll.getClaimAsString("scope")).isEqualTo("payroll:export");
        assertThat(DelegatedTokensView.actingAgent(seenByPayroll)).contains(DownstreamCredentials.SELF);
    }

    /** Reads the actor claim the way lecture six defined it, without depending on the security package's helper. */
    static final class DelegatedTokensView {
        static java.util.Optional<String> actingAgent(Jwt jwt) {
            var actor = jwt.getClaimAsMap("act");
            return actor == null || !(actor.get("sub") instanceof String agent) || agent.isBlank()
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(agent);
        }
    }
}
