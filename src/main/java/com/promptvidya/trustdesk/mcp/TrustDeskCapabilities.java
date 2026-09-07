package com.promptvidya.trustdesk.mcp;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.KnowledgeBase;
import com.promptvidya.trustdesk.domain.TrustDeskDomain;
import com.promptvidya.trustdesk.rag.CallerScope;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.AccessDeniedException;

/**
 * TrustDesk's capabilities as MCP tools. The same rules as the
 * in-process tools: the actor comes from the authenticated transport,
 * ownership and shelf are checked against it, every outcome leaves
 * evidence, and results carry data only — the client fences them on
 * arrival, because a result is untrusted text the moment it crosses
 * the process boundary.
 */
public class TrustDeskCapabilities {

    static final String READ_TICKET = "mcp_read_ticket";
    static final String READ_POLICY = "mcp_read_policy";

    private final TrustDeskDomain domain;
    private final KnowledgeBase knowledge;
    private final AuditTrail audit;

    public TrustDeskCapabilities(TrustDeskDomain domain, KnowledgeBase knowledge, AuditTrail audit) {
        this.domain = Objects.requireNonNull(domain);
        this.knowledge = Objects.requireNonNull(knowledge);
        this.audit = Objects.requireNonNull(audit);
    }

    /** One ticket, only if the authenticated caller owns it. The schema has no actor field on purpose. */
    @Tool(name = "ticket_by_id", description = "Read one TrustDesk ticket the caller owns: id, state, summary.")
    public String ticketById(@ToolParam(description = "Ticket id such as T-1") String ticketId) {
        var actor = McpCaller.current();
        var ticket = domain.ticket(ticketId).orElse(null);
        if (ticket == null || !ticket.owner().equals(actor.subject())) {
            audit.record(actor.subject(), READ_TICKET, ticketId, "REFUSED");
            throw new AccessDeniedException("no such ticket for this caller");
        }
        audit.record(actor.subject(), READ_TICKET, ticketId, "ALLOWED");
        return "ticket=" + ticket.id() + " state=" + ticket.state() + " summary=" + ticket.summary();
    }

    /** One policy article, only from a shelf the caller's scopes name. */
    @Tool(name = "policy_article", description = "Read one policy article by slug from a shelf the caller may read.")
    public String policyArticle(@ToolParam(description = "Article slug such as laptop-refresh") String slug) {
        var actor = McpCaller.current();
        var readable = CallerScope.readableOwnersFor(actor);
        var article = knowledge.article(slug).filter(found -> readable.contains(found.owner())).orElse(null);
        if (article == null) {
            audit.record(actor.subject(), READ_POLICY, slug, "REFUSED");
            throw new AccessDeniedException("no such article for this caller");
        }
        audit.record(actor.subject(), READ_POLICY, slug, "ALLOWED");
        return "slug=" + article.slug() + " owner=" + article.owner() + " title=" + article.title()
                + "\n" + article.body();
    }
}
