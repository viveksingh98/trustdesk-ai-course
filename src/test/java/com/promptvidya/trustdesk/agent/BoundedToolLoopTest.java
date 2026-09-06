package com.promptvidya.trustdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

class BoundedToolLoopTest {

    private static ChatResponse toolCallResponse() {
        var toolCall = new AssistantMessage.ToolCall(
                "call-1",
                "function",
                "trustdesk_access_request",
                """
                {
                  "request": {
                    "subject": "alice",
                    "entitlement": "REPORT_VIEWER",
                    "justification": "loop test"
                  }
                }
                """);
        var message = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse plainAnswer() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
    }

    @Test
    void plainAnswersStopTheLoopImmediately() {
        var loop = new BoundedToolLoop(3);

        assertThat(loop.apply(plainAnswer())).isFalse();
        assertThat(loop.observedRounds()).isZero();
    }

    @Test
    void toolRoundsAreAllowedOnlyUpToTheBound() {
        var loop = new BoundedToolLoop(2);

        assertThat(loop.apply(toolCallResponse())).isTrue();
        assertThat(loop.apply(toolCallResponse())).isTrue();
        assertThat(loop.apply(toolCallResponse())).isFalse();
        assertThat(loop.observedRounds()).isEqualTo(2);
    }

    @Test
    void zeroOrNegativeBoundsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BoundedToolLoop(0));
    }

    @Test
    void aModelThatNeverStopsAskingIsCutOffDeterministically() {
        var modelInvocations = new AtomicInteger();
        var relentlessModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                modelInvocations.incrementAndGet();
                return toolCallResponse();
            }
        };
        var tools = new AccessRequestTools(
                new ToolAuthorizationGuard(new AccessPolicy(Set.of("ROOT_OPERATOR"))),
                UUID::randomUUID);
        var advisor = ToolCallingAdvisor.builder()
                .toolCallingManager(ToolCallingManager.builder().build())
                .toolExecutionEligibilityChecker(new BoundedToolLoop(2))
                .build();

        var response = ChatClient.create(relentlessModel)
                .prompt()
                .user("keep going forever")
                .tools(tools)
                .advisors(advisor)
                .call()
                .chatResponse();

        assertThat(response).isNotNull();
        assertThat(modelInvocations.get()).isLessThanOrEqualTo(4);
    }
}
