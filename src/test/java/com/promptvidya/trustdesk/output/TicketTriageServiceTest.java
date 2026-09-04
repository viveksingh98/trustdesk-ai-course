package com.promptvidya.trustdesk.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.promptvidya.trustdesk.output.TicketTriageService.TicketTriage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class TicketTriageServiceTest {

    private static TicketTriageService serviceReplying(String assistantText) {
        var chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage(assistantText))));
            }
        };
        return new TicketTriageService(ChatClient.create(chatModel));
    }

    @Test
    void wellFormedJsonBecomesATrustedTriage() {
        var service = serviceReplying(
                """
                {"category": "ACCESS", "urgency": "HIGH", "needsHuman": true}
                """);

        var triage = service.triage("rendered prompt");

        assertThat(triage).isEqualTo(new TicketTriage("ACCESS", "HIGH", true));
    }

    @Test
    void unknownCategoryIsRejected() {
        var service = serviceReplying(
                """
                {"category": "ADMIN_OVERRIDE", "urgency": "HIGH", "needsHuman": false}
                """);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.triage("rendered prompt"))
                .withMessageContaining("validation")
                .withMessageNotContaining("ADMIN_OVERRIDE");
    }

    @Test
    void unknownUrgencyIsRejected() {
        var service = serviceReplying(
                """
                {"category": "NETWORK", "urgency": "CATASTROPHIC", "needsHuman": true}
                """);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.triage("rendered prompt"))
                .withMessageContaining("validation")
                .withMessageNotContaining("CATASTROPHIC");
    }
}
