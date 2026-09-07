package com.promptvidya.trustdesk.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.agent.AccessRequestTools;
import com.promptvidya.trustdesk.agent.TicketTools;
import com.promptvidya.trustdesk.agent.TicketTools.Ticket;
import com.promptvidya.trustdesk.authz.ToolPolicy;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.observability.KillSwitch.Mode;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import com.promptvidya.trustdesk.testing.FrozenClock;
import com.promptvidya.trustdesk.testing.ScriptedChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pause the agent, not the application: running answers and runs;
 * read-only still answers and reads but refuses every write; halted
 * makes no model call and answers with one sentence; every flip and
 * every refusal is a row; and a reason is a reference, never a payload.
 */
class KillSwitchTest {

    private final AuditTrail audit = new AuditTrail(FrozenClock.at("2026-09-07T11:00:00Z"));
    private final KillSwitch killSwitch = new KillSwitch(audit);
    private final KillSwitchAdvisor advisor = new KillSwitchAdvisor(killSwitch, new AgentMetrics(new SimpleMeterRegistry()), audit);
    private final ActorContext alice = new ActorContext("alice", Set.of("tickets:read", "access:request"));
    private final ToolContext asAlice = new ToolContext(Map.of("actor", alice));
    private final ToolCallback[] tools = HaltableToolCallback.haltable(killSwitch, ToolPolicy.trustDesk(),
            concat(ToolCallbacks.from(new TicketTools(Map.of("T-1", new Ticket("T-1", "alice", "VPN")))),
                    ToolCallbacks.from(new AccessRequestTools(
                            new ToolAuthorizationGuard(new AccessPolicy(Set.of("ROOT_OPERATOR"))), UUID::randomUUID))));

    private static ToolCallback[] concat(ToolCallback[] first, ToolCallback[] second) {
        var all = new ToolCallback[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private ToolCallback tool(String name) {
        for (var callback : tools) {
            if (callback.getToolDefinition().name().equals(name)) {
                return callback;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private String ask(ScriptedChatModel model) {
        return ChatClient.create(model).prompt().user("status?")
                .advisors(spec -> spec.advisors(advisor).param(BudgetAdvisor.ACTOR_KEY, alice))
                .call().content();
    }

    @Test
    void runningAnswersAndRunsEverything() {
        assertThat(ask(ScriptedChatModel.script().answer("all good"))).isEqualTo("all good");
        assertThat(tool("ticketById").call("{\"ticketId\":\"T-1\"}", asAlice)).contains("VPN");
        assertThat(tool("requestAccess").call(
                "{\"request\":{\"subject\":\"alice\",\"entitlement\":\"REPORT_VIEWER\",\"justification\":\"lab\"}}", asAlice))
                .contains("PENDING_APPROVAL");
    }

    @Test
    void readOnlyStillAnswersAndReadsButRefusesEveryWrite() {
        killSwitch.set(Mode.READ_ONLY, "oncall", "INC-4711 suspicious approvals");

        assertThat(ask(ScriptedChatModel.script().answer("reading only"))).isEqualTo("reading only");
        assertThat(tool("ticketById").call("{\"ticketId\":\"T-1\"}", asAlice)).contains("VPN");
        assertThatThrownBy(() -> tool("requestAccess").call(
                "{\"request\":{\"subject\":\"alice\",\"entitlement\":\"REPORT_VIEWER\",\"justification\":\"lab\"}}", asAlice))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("READ_ONLY");
    }

    @Test
    void haltedMakesNoModelCallAndEveryRefusalIsARow() {
        killSwitch.set(Mode.HALTED, "oncall", "INC-4711 containment");
        var model = ScriptedChatModel.script().answer("never");

        assertThat(ask(model)).isEqualTo(KillSwitchAdvisor.PAUSED_MESSAGE);
        assertThat(model.invocations()).isZero();
        assertThatThrownBy(() -> tool("ticketById").call("{\"ticketId\":\"T-1\"}", asAlice))
                .isInstanceOf(AccessDeniedException.class);

        killSwitch.set(Mode.RUNNING, "oncall", "INC-4711 resolved");
        assertThat(ask(ScriptedChatModel.script().answer("back"))).isEqualTo("back");
        assertThat(audit.all())
                .extracting(AuditEvent::actor, AuditEvent::action, AuditEvent::outcome)
                .containsExactly(
                        tuple("oncall", KillSwitch.ACTION, "HALTED"),
                        tuple("alice", KillSwitchAdvisor.REFUSED_ACTION, "HALTED"),
                        tuple("oncall", KillSwitch.ACTION, "RUNNING"));
    }

    @Test
    void aReasonIsAReferenceNeverAPayload() {
        assertThatThrownBy(() -> killSwitch.set(Mode.HALTED, "oncall", "x".repeat(200)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> killSwitch.set(Mode.HALTED, "oncall", "<script>alert(1)</script>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(killSwitch.mode()).isEqualTo(Mode.RUNNING);
        assertThat(audit.size()).isZero();
    }
}
