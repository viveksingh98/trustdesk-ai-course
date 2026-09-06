package com.promptvidya.trustdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AgentChatServiceTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void authenticatedActorStaysOutsideModelControlledToolSchema() {
        var scenario = executeChat();

        assertThat(scenario.response()).isEqualTo("Request accepted");
        assertThat(scenario.prompt().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        var toolOptions = (ToolCallingChatOptions) scenario.prompt().getOptions();
        assertThat(toolOptions.getToolContext()).containsExactly(
                Map.entry("actor", new ActorContext("alice", Set.of("access:request"))));
        assertThat(toolOptions.getToolCallbacks()).hasSize(1);
        var schema = toolOptions.getToolCallbacks().getFirst().getToolDefinition().inputSchema();
        assertThat(schema)
                .contains("subject", "entitlement", "justification")
                .doesNotContain("actor", "scopes", "ToolContext", "credentials");
    }

    @Test
    void realToolManagerDispatchesAuthenticatedActorToToolCallback() {
        var scenario = executeChat();
        var toolOptions = (ToolCallingChatOptions) scenario.prompt().getOptions();
        var toolCall = new AssistantMessage.ToolCall(
                "call-42",
                "function",
                toolOptions.getToolCallbacks().getFirst().getToolDefinition().name(),
                """
                {
                  "request": {
                    "subject": "alice",
                    "entitlement": "REPORT_VIEWER",
                    "justification": "training lab"
                  }
                }
                """);
        var toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        var syntheticModelResponse = new ChatResponse(List.of(new Generation(toolCallMessage)));

        var executionResult = ToolCallingManager.builder()
                .build()
                .executeToolCalls(scenario.prompt(), syntheticModelResponse);

        var toolResponseMessage = (ToolResponseMessage) executionResult.conversationHistory().getLast();
        var dispatchedResponseData = toolResponseMessage.getResponses().getFirst().responseData();
        assertThat(dispatchedResponseData).contains(REQUEST_ID.toString(), "PENDING_APPROVAL");
    }

    private static ChatScenario executeChat() {
        var capturedPrompt = new AtomicReference<Prompt>();
        var chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                capturedPrompt.set(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Request accepted"))));
            }

            @Override
            public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        var chatClient = ChatClient.create(chatModel);
        var tools = new AccessRequestTools(
                new ToolAuthorizationGuard(new AccessPolicy(Set.of("ROOT_OPERATOR"))),
                () -> REQUEST_ID);
        var service = new AgentChatService(chatClient, tools);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice",
                "not-used",
                List.of(new SimpleGrantedAuthority("access:request")));

        var response = service.chat("Please request report access", authentication);
        return new ChatScenario(response, capturedPrompt.get());
    }

    private record ChatScenario(String response, Prompt prompt) {
    }
}
