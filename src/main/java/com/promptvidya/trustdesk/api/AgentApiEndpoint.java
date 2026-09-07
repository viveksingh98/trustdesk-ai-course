package com.promptvidya.trustdesk.api;

import com.promptvidya.trustdesk.api.TrustDeskChatEndpoint.ChatRequest;
import com.promptvidya.trustdesk.api.TrustDeskChatEndpoint.ChatResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bearer-token door in front of the same chat. Nothing about the
 * conversation changes between the web session and the API token —
 * only the way the caller proved who they are.
 */
@RestController
public final class AgentApiEndpoint {

    public record Caller(String subject, List<String> authorities) {}

    private final TrustDeskChatEndpoint chat;

    public AgentApiEndpoint(TrustDeskChatEndpoint chat) {
        this.chat = Objects.requireNonNull(chat);
    }

    @GetMapping("/api/whoami")
    public Caller whoami(Authentication authentication) {
        var authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
        return new Caller(authentication.getName(), authorities);
    }

    @PostMapping("/api/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, Authentication authentication) {
        return chat.chat(request, authentication);
    }
}
