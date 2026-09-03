package com.promptvidya.trustdesk.api;

import com.promptvidya.trustdesk.chat.ChatService;
import com.promptvidya.trustdesk.memory.ConversationMemory;
import com.promptvidya.trustdesk.memory.ConversationMemory.Turn;
import com.promptvidya.trustdesk.resilience.GuardedModelCall;
import com.promptvidya.trustdesk.resilience.GuardedModelCall.Outcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The section-three checkpoint: identity from Spring Security, history
 * only under that subject, the model call inside the failure guard, and
 * only real answers remembered.
 */
@RestController
public class ChatEndpoint {

    public record ChatRequest(@NotBlank String message) {}

    public record ChatResponse(String reply, boolean answered) {}

    private final ChatService chat;
    private final ConversationMemory memory;
    private final GuardedModelCall guard;

    public ChatEndpoint(ChatService chat, ConversationMemory memory, GuardedModelCall guard) {
        this.chat = Objects.requireNonNull(chat);
        this.memory = Objects.requireNonNull(memory);
        this.guard = Objects.requireNonNull(guard);
    }

    @PostMapping("/chat")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request, Authentication authentication) {
        var subject = authentication.getName();
        var history = memory.history(subject);
        var outcome = guard.call(() -> chat.chat(request.message(), history));
        return switch (outcome) {
            case Outcome.Answer answer -> {
                memory.append(subject, new Turn(request.message(), answer.text()));
                yield new ChatResponse(answer.text(), true);
            }
            case Outcome.Refused refused -> new ChatResponse(refused.reason(), false);
        };
    }
}
