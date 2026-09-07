package com.promptvidya.trustdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.promptvidya.trustdesk.agent.AgentChatService;
import com.promptvidya.trustdesk.api.TrustDeskChatEndpoint.ChatRequest;
import com.promptvidya.trustdesk.memory.ConversationMemory;
import com.promptvidya.trustdesk.memory.ConversationMemory.Turn;
import com.promptvidya.trustdesk.prompt.SupportPromptTemplate;
import com.promptvidya.trustdesk.resilience.GuardedModelCall;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class TrustDeskChatEndpointTest {

    private final AgentChatService agent = mock(AgentChatService.class);
    private final ConversationMemory memory = new ConversationMemory(10);
    private final GuardedModelCall guard = new GuardedModelCall(2, Duration.ofSeconds(2));
    private final TrustDeskChatEndpoint endpoint =
            new TrustDeskChatEndpoint(agent, memory, guard, new SupportPromptTemplate());
    private final TestingAuthenticationToken alice =
            new TestingAuthenticationToken("alice", "n/a", "ROLE_EMPLOYEE");

    @Test
    void answersAreReturnedAndRemembered() {
        when(agent.chat(contains("reset my token"), anyList(), any()))
                .thenReturn("Use the self-service portal.");

        var response = endpoint.chat(new ChatRequest("reset my token"), alice);

        assertThat(response.answered()).isTrue();
        assertThat(response.reply()).isEqualTo("Use the self-service portal.");
        assertThat(memory.history("alice"))
                .containsExactly(new Turn("reset my token", "Use the self-service portal."));
    }

    @Test
    void questionsReachTheAgentFencedAsLabeledData() {
        when(agent.chat(any(), anyList(), any())).thenReturn("noted");

        endpoint.chat(new ChatRequest("reset my token"), alice);

        verify(agent)
                .chat(
                        argThat(
                                prompt ->
                                        prompt.contains("untrusted employee data")
                                                && prompt.contains(
                                                        "<employee_question>\nreset my token\n</employee_question>")),
                        anyList(),
                        any());
    }

    @Test
    void storedHistoryRidesIntoTheNextCall() {
        when(agent.chat(any(), anyList(), any())).thenReturn("noted");
        endpoint.chat(new ChatRequest("first question"), alice);

        endpoint.chat(new ChatRequest("follow-up"), alice);

        when(agent.chat(contains("third"), eq(memory.history("alice")), any()))
                .thenReturn("third answer");
        var response = endpoint.chat(new ChatRequest("third"), alice);
        assertThat(response.reply()).isEqualTo("third answer");
        assertThat(memory.history("alice")).hasSize(3);
    }

    @Test
    void refusalsAreSafeAndNeverRemembered() {
        when(agent.chat(any(), anyList(), any()))
                .thenThrow(new IllegalStateException("provider detail that must not leak"));

        var response = endpoint.chat(new ChatRequest("anything"), alice);

        assertThat(response.answered()).isFalse();
        assertThat(response.reply()).isEqualTo("model call failed");
        assertThat(memory.history("alice")).isEmpty();
    }
}
