package com.promptvidya.trustdesk.authz;

import com.promptvidya.trustdesk.identity.ActorContext;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.access.AccessDeniedException;

/**
 * The decision layer as a decorator: every callback on the belt is
 * wrapped, so a tool is authorized the day it is added or it does not
 * run. The actor is read from the tool context the chat service placed
 * there — never from the model's arguments — and the intent is the
 * caller's stated reason from the same context.
 */
public final class AuthorizedToolCallback implements ToolCallback {

    public static final String ACTOR_KEY = "actor";
    public static final String INTENT_KEY = "intent";
    public static final String DELEGATION_KEY = "delegation";

    private final ToolCallback delegate;
    private final DecisionLayer decisions;

    public AuthorizedToolCallback(ToolCallback delegate, DecisionLayer decisions) {
        this.delegate = Objects.requireNonNull(delegate);
        this.decisions = Objects.requireNonNull(decisions);
    }

    /** Wrap a whole belt at once. */
    public static ToolCallback[] guard(DecisionLayer decisions, ToolCallback... callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> new AuthorizedToolCallback(callback, decisions))
                .toArray(ToolCallback[]::new);
    }

    public static ToolCallbackProvider guard(DecisionLayer decisions, ToolCallbackProvider provider) {
        return () -> guard(decisions, provider.getToolCallbacks());
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /** No context means no actor, and no actor means no run. */
    @Override
    public String call(String toolInput) {
        throw new AccessDeniedException("tool run without a caller context: " + name());
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        if (toolContext == null || !(toolContext.getContext().get(ACTOR_KEY) instanceof ActorContext actor)) {
            throw new AccessDeniedException("tool run without an authenticated actor: " + name());
        }
        var intent = toolContext.getContext().get(INTENT_KEY) instanceof String text ? text : null;
        var chain = toolContext.getContext().get(DELEGATION_KEY) instanceof DelegationChain hops ? hops : null;
        var decision = decisions.decide(actor, name(), intent, chain);
        if (!decision.allowed()) {
            throw new AccessDeniedException(decision.outcome() + ": " + name());
        }
        return delegate.call(toolInput, toolContext);
    }

    private String name() {
        return delegate.getToolDefinition().name();
    }
}
