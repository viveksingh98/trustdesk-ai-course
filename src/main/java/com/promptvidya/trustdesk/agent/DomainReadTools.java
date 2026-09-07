package com.promptvidya.trustdesk.agent;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.KnowledgeBase;
import com.promptvidya.trustdesk.domain.Ticket;
import com.promptvidya.trustdesk.domain.TrustDeskDomain;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.AccessDeniedException;

/**
 * The agent meets the domain, read-only first. Every verb takes its scope
 * from the authenticated actor, calls a domain method instead of a store,
 * and leaves exactly one audit event behind — allowed or refused.
 */
public final class DomainReadTools {

    private final TrustDeskDomain domain;
    private final KnowledgeBase knowledge;
    private final AuditTrail audit;

    public DomainReadTools(TrustDeskDomain domain, KnowledgeBase knowledge, AuditTrail audit) {
        this.domain = Objects.requireNonNull(domain);
        this.knowledge = Objects.requireNonNull(knowledge);
        this.audit = Objects.requireNonNull(audit);
    }

    @Tool(description = "List the open tickets that belong to the authenticated employee")
    public List<Ticket> myTickets(ToolContext toolContext) {
        var actor = authenticatedActor(toolContext);
        var tickets = domain.ticketsOwnedBy(actor.subject());
        audit.record(actor.subject(), "list_tickets", "own", "ALLOWED");
        return tickets;
    }

    @Tool(description = "Read one ticket by id if it belongs to the authenticated employee")
    public Ticket ticketById(String ticketId, ToolContext toolContext) {
        var actor = authenticatedActor(toolContext);
        var ticket = domain.ticket(ticketId).orElse(null);
        if (ticket == null || !ticket.owner().equals(actor.subject())) {
            audit.record(actor.subject(), "read_ticket", ticketId, "REFUSED");
            throw new AccessDeniedException("ticket is not visible to this employee");
        }
        audit.record(actor.subject(), "read_ticket", ticketId, "ALLOWED");
        return ticket;
    }

    @Tool(description = "Read one policy article by its short name, with its owning team")
    public KnowledgeBase.Article policyArticle(String slug, ToolContext toolContext) {
        var actor = authenticatedActor(toolContext);
        var article = knowledge.article(slug).orElse(null);
        if (article == null) {
            audit.record(actor.subject(), "read_article", slug, "REFUSED");
            throw new AccessDeniedException("no such policy article");
        }
        audit.record(actor.subject(), "read_article", slug, "ALLOWED");
        return article;
    }

    private static ActorContext authenticatedActor(ToolContext toolContext) {
        if (toolContext == null
                || !(toolContext.getContext().get("actor") instanceof ActorContext actor)) {
            throw new AccessDeniedException("Authenticated actor context is required");
        }
        return actor;
    }
}
