package com.promptvidya.trustdesk.api;

import com.promptvidya.trustdesk.agent.AgentChatService;
import com.promptvidya.trustdesk.memory.ConversationMemory;
import com.promptvidya.trustdesk.memory.ConversationMemory.Turn;
import com.promptvidya.trustdesk.prompt.SupportPromptTemplate;
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
 * TrustDesk's shipping chat endpoint: the section's four pieces composed.
 *
 * <p>Identity arrives from Spring Security, history is read and written
 * only under that subject, the model call runs inside the failure guard,
 * and only a successful answer is remembered.
 */
@RestController
public final class TrustDeskChatEndpoint {

    public record ChatRequest(@NotBlank String message) {}

    public record ChatResponse(String reply, boolean answered) {}

    private final AgentChatService agent;
    private final ConversationMemory memory;
    private final GuardedModelCall guard;
    private final SupportPromptTemplate promptTemplate;

    public TrustDeskChatEndpoint(
            AgentChatService agent,
            ConversationMemory memory,
            GuardedModelCall guard,
            SupportPromptTemplate promptTemplate) {
        this.agent = Objects.requireNonNull(agent);
        this.memory = Objects.requireNonNull(memory);
        this.guard = Objects.requireNonNull(guard);
        this.promptTemplate = Objects.requireNonNull(promptTemplate);
    }

    @PostMapping("/chat")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request, Authentication authentication) {
        var subject = authentication.getName();
        var history = memory.history(subject);
        var prompt = promptTemplate.render(request.message());
        var outcome = guard.call(() -> agent.chat(prompt, history, authentication));
        return switch (outcome) {
            case Outcome.Answer answer -> {
                memory.append(subject, new Turn(request.message(), answer.text()));
                yield new ChatResponse(answer.text(), true);
            }
            case Outcome.Refused refused -> new ChatResponse(refused.reason(), false);
        };
    }
}
