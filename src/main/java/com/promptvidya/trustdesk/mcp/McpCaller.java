package com.promptvidya.trustdesk.mcp;

import com.promptvidya.trustdesk.identity.ActorContext;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The actor behind an MCP tool call: whoever the transport authenticated
 * on this request — never a tool argument, never a session id. With a
 * synchronous server the tool runs on the request thread, so the same
 * security context the bearer door populated is the one read here.
 */
public final class McpCaller {

    private McpCaller() {}

    public static ActorContext current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("MCP tool calls require an authenticated caller");
        }
        var scopes = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        return new ActorContext(authentication.getName(), scopes);
    }
}
