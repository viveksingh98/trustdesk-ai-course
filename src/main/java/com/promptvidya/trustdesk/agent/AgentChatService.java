package com.promptvidya.trustdesk.agent;

import java.util.Objects;
import java.util.stream.Collectors;

import com.promptvidya.trustdesk.identity.ActorContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

public final class AgentChatService {

    private final ChatClient chatClient;
    private final AccessRequestTools tools;

    public AgentChatService(ChatClient chatClient, AccessRequestTools tools) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
    }

    public String chat(String prompt, Authentication authentication) {
        var actor = actorFrom(authentication);
        return chatClient.prompt()
                .user(prompt)
                .tools(tools)
                .toolContext(java.util.Map.of("actor", actor))
                .call()
                .content();
    }

    private static ActorContext actorFrom(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authenticated application state is required");
        }
        var scopes = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        try {
            return new ActorContext(authentication.getName(), scopes);
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("Authenticated application state is invalid", exception);
        }
    }

    /**
     * Chat with the caller's stored history riding ahead of the new
     * prompt. History enters as ordinary user and assistant messages —
     * conversation, never instructions — exactly as the contract taught.
     */
    public String chat(
            String prompt,
            java.util.List<com.promptvidya.trustdesk.memory.ConversationMemory.Turn> history,
            Authentication authentication) {
        var actor = actorFrom(authentication);
        var spec = chatClient.prompt();
        for (var turn : history) {
            spec = spec.messages(
                    new org.springframework.ai.chat.messages.UserMessage(turn.userText()),
                    new org.springframework.ai.chat.messages.AssistantMessage(turn.assistantText()));
        }
        return spec.user(prompt)
                .tools(tools)
                .toolContext(java.util.Map.of("actor", actor))
                .call()
                .content();
    }
}
