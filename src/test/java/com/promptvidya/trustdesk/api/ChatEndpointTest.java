package com.promptvidya.trustdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.promptvidya.trustdesk.api.ChatEndpoint.ChatRequest;
import com.promptvidya.trustdesk.chat.ChatService;
import com.promptvidya.trustdesk.memory.ConversationMemory;
import com.promptvidya.trustdesk.memory.ConversationMemory.Turn;
import com.promptvidya.trustdesk.resilience.GuardedModelCall;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class ChatEndpointTest {

    private final ChatService chat = mock(ChatService.class);
    private final ConversationMemory memory = new ConversationMemory(10);
    private final GuardedModelCall guard = new GuardedModelCall(2, Duration.ofSeconds(2));
    private final ChatEndpoint endpoint = new ChatEndpoint(chat, memory, guard);
    private final TestingAuthenticationToken alice =
            new TestingAuthenticationToken("alice", "n/a", "ROLE_EMPLOYEE");

    @Test
    void answersAreReturnedAndRemembered() {
        when(chat.chat(eq("reset my token"), anyList()))
                .thenReturn("Use the self-service portal.");

        var response = endpoint.chat(new ChatRequest("reset my token"), alice);

        assertThat(response.answered()).isTrue();
        assertThat(memory.history("alice"))
                .containsExactly(new Turn("reset my token", "Use the self-service portal."));
    }

    @Test
    void storedHistoryRidesIntoTheNextCall() {
        when(chat.chat(any(), anyList())).thenReturn("noted");
        endpoint.chat(new ChatRequest("first question"), alice);

        when(chat.chat(eq("second"), eq(memory.history("alice")))).thenReturn("second answer");
        var response = endpoint.chat(new ChatRequest("second"), alice);

        assertThat(response.reply()).isEqualTo("second answer");
        assertThat(memory.history("alice")).hasSize(2);
    }

    @Test
    void refusalsAreSafeAndNeverRemembered() {
        when(chat.chat(any(), anyList()))
                .thenThrow(new IllegalStateException("provider detail that must not leak"));

        var response = endpoint.chat(new ChatRequest("anything"), alice);

        assertThat(response.answered()).isFalse();
        assertThat(response.reply()).isEqualTo("model call failed");
        assertThat(memory.history("alice")).isEmpty();
    }
}
