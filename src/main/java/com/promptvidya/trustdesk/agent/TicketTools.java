package com.promptvidya.trustdesk.agent;

import com.promptvidya.trustdesk.identity.ActorContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.AccessDeniedException;

/**
 * The shrunk shape of a ticket tool: no query surface, scope taken from
 * the authenticated actor rather than from arguments, ownership checked
 * inside every verb, and one refusal wording for missing and foreign
 * tickets alike so the tool is not an existence oracle.
 */
public final class TicketTools {

    public record Ticket(String id, String owner, String summary) {}

    private final Map<String, Ticket> tickets;

    public TicketTools(Map<String, Ticket> tickets) {
        this.tickets = Map.copyOf(Objects.requireNonNull(tickets));
    }

    @Tool(description = "List the open tickets that belong to the authenticated employee")
    public List<Ticket> myOpenTickets(ToolContext toolContext) {
        var actor = authenticatedActor(toolContext);
        return tickets.values().stream()
                .filter(ticket -> ticket.owner().equals(actor.subject()))
                .toList();
    }

    @Tool(description = "Read one ticket by id if it belongs to the authenticated employee")
    public Ticket ticketById(String ticketId, ToolContext toolContext) {
        var actor = authenticatedActor(toolContext);
        var ticket = tickets.get(ticketId);
        if (ticket == null || !ticket.owner().equals(actor.subject())) {
            throw new AccessDeniedException("ticket is not visible to this employee");
        }
        return ticket;
    }

    private static ActorContext authenticatedActor(ToolContext toolContext) {
        if (toolContext == null
                || !(toolContext.getContext().get("actor") instanceof ActorContext actor)) {
            throw new AccessDeniedException("Authenticated actor context is required");
        }
        return actor;
    }
}
