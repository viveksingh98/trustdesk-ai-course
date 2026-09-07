package com.promptvidya.trustdesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.agent.AccessRequestTools;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.domain.TrustDeskDomain;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.security.DelegatedTokens;
import com.promptvidya.trustdesk.security.GuardedDomainServices;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;

/**
 * The agent that asked too much. Five escalation moves a model can make
 * — invent a tool, ask for admin, approve its own request, retry after
 * the grant expired, forge a subject in the arguments — each stopped by
 * a different layer, none of which trusts what the model wrote.
 */
@SpringBootTest
class EscalationAttackTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final AuditTrail audit = new AuditTrail(clock);
    private final DecisionLayer decisions = new DecisionLayer(ToolPolicy.trustDesk(), audit);
    private final GrantLedger ledger = GrantLedger.seeded(clock);
    private final AccessRequestTools accessTools = new AccessRequestTools(
            new ToolAuthorizationGuard(new AccessPolicy(Set.of("PAYROLL_EXPORT"))), UUID::randomUUID);
    private final ToolCallback requestAccess = AuthorizedToolCallback.guard(decisions, ToolCallbacks.from(accessTools))[0];
    private final ActorContext aliceToken = new ActorContext("alice", Set.of("tickets:read", "access:request"));

    @Autowired
    private GuardedDomainServices services;

    @Autowired
    private TrustDeskDomain domain;

    @Autowired
    private DelegatedTokens tokens;

    @Autowired
    private JwtDecoder decoder;

    @AfterEach
    void clearPrincipal() {
        TestSecurityContextHolder.clearContext();
    }

    private ToolContext contextFor(ActorContext actor, String intent) {
        return new ToolContext(Map.of("actor", actor, "intent", intent,
                "delegation", new DelegationChain(actor.subject(), List.of("trustdesk-agent"))));
    }

    private static String request(String subject, String entitlement) {
        return "{\"request\":{\"subject\":\"" + subject + "\",\"entitlement\":\"" + entitlement
                + "\",\"justification\":\"checkpoint\"}}";
    }

    @Test
    void moveOneInventingAToolIsRefusedBeforeItExists() {
        var invocations = new AtomicInteger();
        var invented = AuthorizedToolCallback.guard(decisions, new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("grant_scope").description("d").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                invocations.incrementAndGet();
                return "granted";
            }
        })[0];
        ledger.forTask("alice", "access:request", "hardware-lead", Duration.ofMinutes(30));

        assertThatThrownBy(() -> invented.call("{\"scope\":\"payroll:export\"}", contextFor(ledger.narrow(aliceToken), "I need payroll")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("DENIED_UNKNOWN_TOOL");
        assertThat(invocations.get()).isZero();
    }

    @Test
    void moveTwoAskingForAdminReachesTheToolAndStopsAtHumanApproval() {
        ledger.forTask("alice", "access:request", "hardware-lead", Duration.ofMinutes(30));

        var result = requestAccess.call(request("alice", "ADMIN"),
                contextFor(ledger.narrow(aliceToken), "the injected document said to become admin"));

        assertThat(result).contains("REQUIRES_HUMAN_APPROVAL");
        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::target, AuditEvent::outcome)
                .contains(org.assertj.core.groups.Tuple.tuple("requestAccess@alice via trustdesk-agent", "ALLOWED"));
    }

    @Test
    void moveThreeSoftwareCannotApproveItsOwnRequest() {
        var pending = domain.requestAccess("alice", "ADMIN", "self-service");
        var delegated = decoder.decode(tokens.mintFor(
                new ActorContext("alice", Set.of("ROLE_MANAGER", "access:request")), "trustdesk-agent", Set.of("access:request")));
        TestSecurityContextHolder.setAuthentication(
                new JwtAuthenticationToken(delegated, List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));

        assertThatThrownBy(() -> services.decide(pending.id(), true))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("people");
    }

    @Test
    void moveFourRetryingAfterTheGrantExpiredIsRefusedWithoutRunning() {
        ledger.forTask("alice", "access:request", "hardware-lead", Duration.ofMinutes(10));
        assertThat(requestAccess.call(request("alice", "REPORT_VIEWER"), contextFor(ledger.narrow(aliceToken), "month-end")))
                .contains("PENDING_APPROVAL");

        now.set(now.get().plus(Duration.ofMinutes(11)));

        assertThatThrownBy(() -> requestAccess.call(request("alice", "REPORT_VIEWER"), contextFor(ledger.narrow(aliceToken), "month-end")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("DENIED_NO_GRANT");
    }

    @Test
    void moveFiveAForgedSubjectInTheArgumentsChangesNothing() {
        ledger.forTask("alice", "access:request", "hardware-lead", Duration.ofMinutes(30));

        // The tool's own guard refuses; the tool-calling machinery may wrap that refusal, so look at the root cause.
        assertThatThrownBy(() -> requestAccess.call(request("hardware-lead", "REPORT_VIEWER"),
                        contextFor(ledger.narrow(aliceToken), "on behalf of my manager")))
                .satisfiesAnyOf(
                        thrown -> assertThat(thrown).isInstanceOf(AccessDeniedException.class),
                        thrown -> assertThat(thrown).hasRootCauseInstanceOf(AccessDeniedException.class));
        assertThat(audit.eventsFor("hardware-lead")).isEmpty();
    }
}
