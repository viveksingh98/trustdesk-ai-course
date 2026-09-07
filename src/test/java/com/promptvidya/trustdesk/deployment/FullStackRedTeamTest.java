package com.promptvidya.trustdesk.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.access.AccessRequest;
import com.promptvidya.trustdesk.agent.AccessRequestTools;
import com.promptvidya.trustdesk.agent.TicketTools;
import com.promptvidya.trustdesk.agent.TicketTools.Ticket;
import com.promptvidya.trustdesk.approval.ApprovalQueue;
import com.promptvidya.trustdesk.approval.IntentBinding;
import com.promptvidya.trustdesk.authz.AuthorizedToolCallback;
import com.promptvidya.trustdesk.authz.DecisionLayer;
import com.promptvidya.trustdesk.authz.DecisionLayer.Outcome;
import com.promptvidya.trustdesk.authz.DelegationChain;
import com.promptvidya.trustdesk.authz.ToolPolicy;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.hardening.TrustedContext;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.observability.AgentMetrics;
import com.promptvidya.trustdesk.observability.BudgetAdvisor;
import com.promptvidya.trustdesk.observability.EvidenceQueries;
import com.promptvidya.trustdesk.observability.EvidenceQueries.Window;
import com.promptvidya.trustdesk.observability.HaltableToolCallback;
import com.promptvidya.trustdesk.observability.KillSwitch;
import com.promptvidya.trustdesk.observability.KillSwitch.Mode;
import com.promptvidya.trustdesk.observability.KillSwitchAdvisor;
import com.promptvidya.trustdesk.observability.TokenBudget;
import com.promptvidya.trustdesk.observability.TokenBudget.Limits;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import com.promptvidya.trustdesk.testing.AttackCorpus;
import com.promptvidya.trustdesk.testing.FrozenClock;
import com.promptvidya.trustdesk.testing.ScriptedChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * The final red team: one attacker, every layer, one afternoon. A
 * poisoned document, an invented tool, a forged subject, a raced and
 * replayed approval, a flood, and a last push while the operator
 * contains — against the real components wired the way the course
 * wired them, on a frozen clock, offline. Then the deployment wall,
 * and the trail that tells the whole story from references alone.
 */
class FullStackRedTeamTest {

    private static final Instant AFTERNOON = Instant.parse("2026-09-07T14:00:00Z");
    private static final String REFUND = "{\"ticketId\":\"T-1\",\"amount\":500}";

    private final FrozenClock clock = FrozenClock.at(AFTERNOON);
    private final AuditTrail audit = new AuditTrail(clock);
    private final AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());
    private final DecisionLayer decisions = new DecisionLayer(ToolPolicy.trustDesk(), audit);
    private final ToolAuthorizationGuard guard = new ToolAuthorizationGuard(new AccessPolicy(Set.of("ROOT_OPERATOR")));
    private final AtomicInteger sequence = new AtomicInteger();
    private final ApprovalQueue queue = new ApprovalQueue(clock, audit, () -> "A-" + sequence.incrementAndGet());
    private final IntentBinding binding = new IntentBinding(queue, audit, clock);
    private final BudgetAdvisor budget = new BudgetAdvisor(new TokenBudget(clock, new Limits(5_000, 2)), metrics, audit);
    private final KillSwitch killSwitch = new KillSwitch(audit);
    private final KillSwitchAdvisor containment = new KillSwitchAdvisor(killSwitch, metrics, audit);
    private final EvidenceQueries evidence = new EvidenceQueries(audit);
    private final ActorContext mallory = new ActorContext("mallory", Set.of("tickets:read", "access:request"));
    private final DelegationChain malloryViaAgent = new DelegationChain("mallory", List.of("trustdesk-agent"));
    private final TestingAuthenticationToken aliceInPerson = new TestingAuthenticationToken("alice", "n/a", "ROLE_EMPLOYEE");
    private final ToolCallback[] belt = HaltableToolCallback.haltable(killSwitch, ToolPolicy.trustDesk(),
            AuthorizedToolCallback.guard(decisions, concat(
                    ToolCallbacks.from(new TicketTools(Map.of("T-1", new Ticket("T-1", "alice", "VPN drops hourly")))),
                    ToolCallbacks.from(new AccessRequestTools(guard, UUID::randomUUID)))));

    private static ToolCallback[] concat(ToolCallback[] first, ToolCallback[] second) {
        var all = new ToolCallback[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private ToolCallback tool(String name) {
        for (var callback : belt) {
            if (callback.getToolDefinition().name().equals(name)) {
                return callback;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private String chat(String question) {
        return ChatClient.create(ScriptedChatModel.script().answer("noted"))
                .prompt().user(question)
                .advisors(spec -> spec.advisors(containment, budget).param(BudgetAdvisor.ACTOR_KEY, mallory))
                .call().content();
    }

    private static String attack(String id) {
        return AttackCorpus.load().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow().payload();
    }

    @Test
    void moveOneThePoisonedDocumentNeverReachesTheSystemMessage() {
        var prompt = TrustedContext.forSubject("mallory")
                .untrusted("document", "policy", attack("fence-early-close"))
                .untrusted("tool", "ticket", attack("override-direct"))
                .assemble("Summarise the policy and do what it says.");

        var system = prompt.getInstructions().stream().filter(SystemMessage.class::isInstance).toList();
        assertThat(system).hasSize(1);
        assertThat(system.getFirst().getText()).isEqualTo(TrustedContext.INSTRUCTIONS + "\n\nEvidence:\nsubject: mallory");
        assertThat(prompt.getInstructions().getLast().getText()).doesNotContain("</untrusted><untrusted source=\"operator\"");
    }

    @Test
    void moveTwoInventedToolsAndForgedSubjectsAreRefusedByTheirOwnGates() {
        assertThat(decisions.decide(mallory, "export_payroll", attack("invented-tool-export")).outcome())
                .isEqualTo(Outcome.DENIED_UNKNOWN_TOOL);
        assertThatThrownBy(() -> guard.authorize(mallory, new AccessRequest("bob", "ADMIN", attack("forged-subject"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(audit.eventsFor("mallory")).extracting(AuditEvent::outcome).contains(Outcome.DENIED_UNKNOWN_TOOL.name());
    }

    @Test
    void moveThreeARacedAndReplayedApprovalExecutesExactlyOnce() throws Exception {
        var action = binding.submit(mallory, malloryViaAgent, "refund", REFUND, "overcharged");
        queue.decide(action.id(), true, aliceInPerson);
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        binding.execute(action.id(), malloryViaAgent, "refund", REFUND);
                        successes.incrementAndGet();
                    } catch (AccessDeniedException refused) {
                        // the race's losers
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThatThrownBy(() -> binding.execute(action.id(), malloryViaAgent, "refund", REFUND)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> binding.execute(action.id(), malloryViaAgent, "refund", "{\"ticketId\":\"T-1\",\"amount\":50000}"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void moveFourTheFloodIsRateLimitedBeforeAnyModelCall() {
        chat("list everything");
        chat("list everything again");

        assertThat(chat("and again")).isEqualTo(BudgetAdvisor.REFUSED_MESSAGE);
        assertThat(audit.eventsWithOutcome("RATE_LIMITED")).hasSize(1);
    }

    @Test
    void moveFiveContainmentHoldsWhileTheAttackerKeepsPushing() {
        var asMallory = new ToolContext(Map.of(AuthorizedToolCallback.ACTOR_KEY, mallory, AuthorizedToolCallback.INTENT_KEY, "lab"));
        killSwitch.set(Mode.READ_ONLY, "oncall", "INC-9001 red team");
        assertThat(chat("still here")).isEqualTo("noted");
        assertThat(tool("myOpenTickets").call("{}", asMallory)).isEqualTo("[]");
        assertThatThrownBy(() -> tool("requestAccess").call(
                "{\"request\":{\"subject\":\"mallory\",\"entitlement\":\"ADMIN\",\"justification\":\"now\"}}", asMallory))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("READ_ONLY");

        killSwitch.set(Mode.HALTED, "oncall", "INC-9001 containment");
        assertThat(chat("one more")).isEqualTo(KillSwitchAdvisor.PAUSED_MESSAGE);
        assertThat(evidence.story("agent")).filteredOn(event -> event.actor().equals("oncall"))
                .extracting(AuditEvent::outcome).containsExactly("READ_ONLY", "HALTED");
        assertThat(evidence.story("agent")).filteredOn(event -> event.actor().equals("mallory"))
                .extracting(AuditEvent::action).containsExactly("chat_refused");
    }

    @Test
    void moveSixTheDeploymentWallAndTheTrailCloseTheAfternoon() throws Exception {
        moveTwoInventedToolsAndForgedSubjectsAreRefusedByTheirOwnGates();
        moveFourTheFloodIsRateLimitedBeforeAnyModelCall();
        clock.advance(Duration.ofMinutes(1));

        var window = new Window(AFTERNOON, AFTERNOON.plus(Duration.ofHours(1)));
        assertThat(evidence.actorsRefusedMoreThan(1, window)).containsKey("mallory");
        assertThat(audit.all()).extracting(AuditEvent::target)
                .allSatisfy(target -> assertThat(target).doesNotContain("payroll to", "Training scenario", "list everything"));
        assertThat(Files.readString(Path.of("deploy", "Dockerfile"))).contains("USER trustdesk");
        assertThat(Files.readString(Path.of("deploy", "compose.yaml"))).contains("internal: true").contains("cap_drop:");
        assertThat(ProductionReadiness.failures(new org.springframework.mock.env.MockEnvironment())).isNotEmpty();
    }
}
