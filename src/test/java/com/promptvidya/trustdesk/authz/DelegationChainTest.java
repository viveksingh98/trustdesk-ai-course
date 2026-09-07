package com.promptvidya.trustdesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.mcp.DownstreamCredentials;
import com.promptvidya.trustdesk.security.DelegatedTokens;
import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import com.promptvidya.trustdesk.security.McpDoor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;

/**
 * Proving who asked for what: a person alone, a person through the
 * agent, a person through the agent through the MCP server — read from
 * nested actor claims and written into every decision's evidence.
 */
@SpringBootTest
class DelegationChainTest {

    @Autowired
    private DelegatedTokens tokens;

    @Autowired
    private DownstreamCredentials credentials;

    @Autowired
    private DevelopmentJwtKeys keys;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private JwtEncoder encoder;

    private final ActorContext alice = new ActorContext("alice", Set.of("policies:it-hardware", "access:request"));

    @AfterEach
    void clearPrincipal() {
        TestSecurityContextHolder.clearContext();
    }

    @Test
    void aPersonActingDirectlyHasNoHops() {
        var chain = DelegationChain.fromAuthentication(new TestingAuthenticationToken("alice", "n/a", "ROLE_EMPLOYEE"));

        assertThat(chain.delegated()).isFalse();
        assertThat(chain.describe()).isEqualTo("alice");
        assertThat(chain.reference("requestAccess")).isEqualTo("requestAccess@alice");
    }

    @Test
    void oneHopReadsTheAgentFromTheActorClaim() {
        var delegated = decoder.decode(tokens.mintFor(alice, "trustdesk-agent", Set.of("policies:it-hardware")));

        var chain = DelegationChain.fromJwt(delegated);

        assertThat(chain.subject()).isEqualTo("alice");
        assertThat(chain.actors()).containsExactly("trustdesk-agent");
        assertThat(chain.describe()).isEqualTo("alice via trustdesk-agent");
    }

    @Test
    void twoHopsNestInTravelOrder() throws Exception {
        var now = Instant.now();
        var inbound = JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(McpDoor.MCP_AUDIENCE))
                .subject("alice")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .claim("scope", "policies:it-hardware payroll:export")
                .claim("act", Map.of("sub", "trustdesk-agent"))
                .build();
        var inboundJwt = McpDoor.mcpDecoder(keys).decode(encoder.encode(JwtEncoderParameters.from(inbound)).getTokenValue());
        TestSecurityContextHolder.setAuthentication(
                new JwtAuthenticationToken(inboundJwt, List.of(new SimpleGrantedAuthority("payroll:export"))));

        var downstream = credentials.forAudience("payroll-api", Set.of("payroll:export"));
        var seenByPayroll = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build().decode(downstream);

        assertThat(DelegationChain.fromJwt(seenByPayroll).describe())
                .isEqualTo("alice via trustdesk-agent via trustdesk-mcp");
    }

    @Test
    void everyDecisionCarriesTheChainAsItsTarget() {
        var audit = new AuditTrail(Clock.systemUTC());
        var decisions = new DecisionLayer(ToolPolicy.trustDesk(), audit);
        var chain = new DelegationChain("alice", List.of("trustdesk-agent"));

        decisions.decide(alice, "requestAccess", "laptop for the new hire", chain);

        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .containsExactly(tuple(DecisionLayer.ACTION, "requestAccess@alice via trustdesk-agent", "ALLOWED"));
    }

    @Test
    void chainsRefuseBlankHopsAndStayShortReferences() {
        assertThatThrownBy(() -> new DelegationChain("alice", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        var longChain = new DelegationChain("alice", java.util.Collections.nCopies(40, "a-rather-long-actor-name"));

        assertThat(longChain.reference("tool").length()).isEqualTo(DelegationChain.MAXIMUM_REFERENCE_CHARACTERS);
    }
}
