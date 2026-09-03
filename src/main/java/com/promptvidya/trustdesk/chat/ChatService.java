package com.promptvidya.trustdesk.chat;

import com.promptvidya.trustdesk.memory.ConversationMemory.Turn;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * TrustDesk's model doorway at the end of section three: one client,
 * stored history riding ahead of the new prompt as plain conversation.
 */
public class ChatService {

    private final ChatClient chatClient;

    public ChatService(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
    }

    /** Sends the prompt with the caller's history as ordinary messages. */
    public String chat(String prompt, List<Turn> history) {
        var spec = chatClient.prompt();
        for (var turn : history) {
            spec = spec.messages(
                    new UserMessage(turn.userText()),
                    new AssistantMessage(turn.assistantText()));
        }
        return spec.user(prompt).call().content();
    }
}
