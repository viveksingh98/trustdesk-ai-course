package com.promptvidya.trustdesk.output;

import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Asks the model for a triage decision and refuses to hand downstream
 * code anything outside TrustDesk's expected shape: a known category, a
 * known urgency, and an explicit human-handoff flag.
 */
public final class TicketTriageService {

    /** The reply shape downstream code is allowed to trust. */
    public record TicketTriage(
            String category, String urgency, boolean needsHuman) {}

    static final Set<String> KNOWN_CATEGORIES =
            Set.of("ACCESS", "HARDWARE", "SOFTWARE", "NETWORK", "OTHER");

    static final Set<String> KNOWN_URGENCIES = Set.of("LOW", "NORMAL", "HIGH");

    private final ChatClient chatClient;

    public TicketTriageService(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient);
    }

    public TicketTriage triage(String renderedPrompt) {
        var reply = chatClient.prompt()
                .user(renderedPrompt)
                .call()
                .entity(TicketTriage.class);
        return validated(reply);
    }

    private static TicketTriage validated(TicketTriage reply) {
        if (reply == null
                || !KNOWN_CATEGORIES.contains(reply.category())
                || !KNOWN_URGENCIES.contains(reply.urgency())) {
            throw new IllegalStateException("model reply failed validation");
        }
        return reply;
    }
}
