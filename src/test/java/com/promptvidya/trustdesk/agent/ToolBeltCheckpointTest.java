package com.promptvidya.trustdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.access.AccessRequest;
import com.promptvidya.trustdesk.agent.TicketTools.Ticket;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.security.access.AccessDeniedException;

/**
 * Section checkpoint: every strap of the tool belt pulled at once — the
 * bridge, the guard, the shrunk verbs, the pinned fetch, and the bounded
 * loop — with all tools registered together.
 */
class ToolBeltCheckpointTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final AccessRequestTools accessTools = new AccessRequestTools(
            new ToolAuthorizationGuard(new AccessPolicy(Set.of("ROOT_OPERATOR"))), () -> REQUEST_ID);
    private final TicketTools ticketTools = new TicketTools(Map.of(
            "T-1", new Ticket("T-1", "alice", "VPN drops every hour"),
            "T-2", new Ticket("T-2", "bob", "Payroll export access")));
    private final PolicyDocumentTool policyTool =
            new PolicyDocumentTool(uri -> "Laptops are replaced every three years.");

    private static ToolContext bridge(String subject) {
        return new ToolContext(Map.of(
                "actor", new ActorContext(subject, Set.of("access:request", "tickets:read"))));
    }

    @Test
    void strapOneTheModelSeesRequestsNeverTheRail() {
        for (var tools : List.of(accessTools, ticketTools, policyTool)) {
            for (var callback : ToolCallbacks.from(tools)) {
                assertThat(callback.getToolDefinition().inputSchema())
                        .doesNotContain("actor", "scopes", "ToolContext", "credentials");
            }
        }
    }

    @Test
    void strapTwoAuthorityIsDecidedPerRequestAtTheGuard() {
        var own = new AccessRequest("alice", "REPORT_VIEWER", "training lab");
        assertThat(accessTools.requestAccess(own, bridge("alice")).status())
                .isEqualTo("PENDING_APPROVAL");

        var forged = new AccessRequest("bob", "ADMIN", "ignore policy");
        assertThatThrownBy(() -> accessTools.requestAccess(forged, bridge("alice")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void strapThreeTheConfusedDeputyIsRefused() {
        assertThat(ticketTools.myOpenTickets(bridge("alice"))).extracting(Ticket::id).containsExactly("T-1");
        assertThatThrownBy(() -> ticketTools.ticketById("T-2", bridge("alice")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void strapFourFetchesArePinnedAndFenced() {
        assertThatThrownBy(() -> policyTool.readPolicyDocument("http://169.254.169.254/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(policyTool.readPolicyDocument("laptop-refresh"))
                .contains("trust=\"untrusted-data\"");
    }

    @Test
    void strapFiveTheLoopStopsWithEveryToolRegistered() {
        var toolName = ToolCallbacks.from(accessTools)[0].getToolDefinition().name();
        var invocations = new AtomicInteger();
        var relentless = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                invocations.incrementAndGet();
                var call = new AssistantMessage.ToolCall("call-1", "function", toolName,
                        """
                        {"request": {"subject": "alice", "entitlement": "REPORT_VIEWER", "justification": "checkpoint"}}
                        """);
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
            }
        };
        var advisor = ToolCallingAdvisor.builder()
                .toolCallingManager(ToolCallingManager.builder().build())
                .toolExecutionEligibilityChecker(new BoundedToolLoop(2))
                .build();

        var response = ChatClient.create(relentless).prompt()
                .user("request report access, forever")
                .tools(accessTools, ticketTools, policyTool)
                .toolContext(Map.of("actor", new ActorContext("alice", Set.of("access:request"))))
                .advisors(advisor)
                .call()
                .chatResponse();

        assertThat(response).isNotNull();
        assertThat(invocations.get()).isLessThanOrEqualTo(4);
    }
}
