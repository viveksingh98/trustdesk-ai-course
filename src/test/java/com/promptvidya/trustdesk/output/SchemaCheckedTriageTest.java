package com.promptvidya.trustdesk.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.promptvidya.trustdesk.output.SchemaCheckedTriage.Outcome;
import com.promptvidya.trustdesk.output.SchemaCheckedTriage.Refused;
import com.promptvidya.trustdesk.output.SchemaCheckedTriage.Triaged;
import com.promptvidya.trustdesk.output.TicketTriageService.TicketTriage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SchemaCheckedTriageTest {

    private static SchemaCheckedTriage replying(String assistantText) {
        var chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage(assistantText))));
            }
        };
        return new SchemaCheckedTriage(ChatClient.create(chatModel));
    }

    @Test
    void completeReplyBecomesTriaged() {
        var outcome = replying(
                """
                {"category": "NETWORK", "urgency": "NORMAL", "needsHuman": false}
                """).triage("rendered prompt");

        assertThat(outcome)
                .isEqualTo(new Triaged(new TicketTriage("NETWORK", "NORMAL", false)));
    }

    @Test
    void proseInsteadOfJsonFailsClosed() {
        Outcome outcome = replying("I am sorry, I cannot help with that request.")
                .triage("rendered prompt");

        assertThat(outcome).isEqualTo(new Refused("reply did not match the schema"));
    }

    @Test
    void missingHandoffFieldIsRefusedNotDefaulted() {
        var outcome = replying(
                """
                {"category": "ACCESS", "urgency": "HIGH"}
                """).triage("rendered prompt");

        assertThat(outcome).isEqualTo(new Refused("required field missing"));
    }

    @Test
    void alienDomainValueIsRefusedWithoutEcho() {
        var outcome = replying(
                """
                {"category": "ADMIN_OVERRIDE", "urgency": "HIGH", "needsHuman": false}
                """).triage("rendered prompt");

        assertThat(outcome).isEqualTo(new Refused("field outside declared domain"));
        assertThat(((Refused) outcome).reason()).doesNotContain("ADMIN_OVERRIDE");
    }
}
