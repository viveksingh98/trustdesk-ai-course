package com.promptvidya.trustdesk.output;

import com.promptvidya.trustdesk.output.TicketTriageService.TicketTriage;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Production triage: the wire shape may be missing anything, conversion
 * may fail entirely, and every call still ends in exactly one typed
 * outcome whose reason never echoes the reply.
 */
public final class SchemaCheckedTriage {

    /** Every call ends in exactly one of these two shapes. */
    public sealed interface Outcome permits Triaged, Refused {}

    public record Triaged(TicketTriage triage) implements Outcome {}

    public record Refused(String reason) implements Outcome {}

    /** Wire shape: wrapper types keep absence visible. */
    record WireTriage(String category, String urgency, Boolean needsHuman) {}

    private final ChatClient chatClient;

    public SchemaCheckedTriage(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient);
    }

    public Outcome triage(String renderedPrompt) {
        final WireTriage wire;
        try {
            wire = chatClient.prompt()
                    .user(renderedPrompt)
                    .call()
                    .entity(WireTriage.class);
        } catch (RuntimeException conversionFailure) {
            return new Refused("reply did not match the schema");
        }
        return validated(wire);
    }

    private static Outcome validated(WireTriage wire) {
        if (wire == null
                || wire.category() == null
                || wire.urgency() == null
                || wire.needsHuman() == null) {
            return new Refused("required field missing");
        }
        if (!TicketTriageService.KNOWN_CATEGORIES.contains(wire.category())
                || !TicketTriageService.KNOWN_URGENCIES.contains(wire.urgency())) {
            return new Refused("field outside declared domain");
        }
        return new Triaged(
                new TicketTriage(wire.category(), wire.urgency(), wire.needsHuman()));
    }
}
