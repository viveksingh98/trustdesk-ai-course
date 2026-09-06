package com.promptvidya.trustdesk.agent;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.promptvidya.trustdesk.access.AccessDecision;
import com.promptvidya.trustdesk.access.AccessRequest;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.AccessDeniedException;

public final class AccessRequestTools {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccessRequestTools.class);
    private final ToolAuthorizationGuard guard;
    private final Supplier<UUID> requestIdSupplier;

    public AccessRequestTools(ToolAuthorizationGuard guard, Supplier<UUID> requestIdSupplier) {
        this.guard = Objects.requireNonNull(guard, "guard must not be null");
        this.requestIdSupplier = Objects.requireNonNull(requestIdSupplier, "requestIdSupplier must not be null");
    }

    @Tool(description = "Request an entitlement for the authenticated employee")
    public AccessDecision requestAccess(AccessRequest request, ToolContext toolContext) {
        var actor = authenticatedActor(toolContext);
        var status = guard.authorize(actor, request);
        var requestId = Objects.requireNonNull(requestIdSupplier.get(), "requestIdSupplier returned null");
        var decision = new AccessDecision(requestId, status);

        LOGGER.atInfo()
                .addKeyValue("actor", actor.subject())
                .addKeyValue("action", "request_access")
                .addKeyValue("target", request.entitlement())
                .addKeyValue("decision", decision.status())
                .addKeyValue("requestId", decision.requestId())
                .log("trustdesk_access_request");

        return decision;
    }

    private static ActorContext authenticatedActor(ToolContext toolContext) {
        if (toolContext == null) {
            throw new AccessDeniedException("Authenticated actor context is required");
        }
        var actor = toolContext.getContext().get("actor");
        if (!(actor instanceof ActorContext actorContext)) {
            throw new AccessDeniedException("Authenticated actor context is required");
        }
        return actorContext;
    }
}
