package com.promptvidya.trustdesk.domain;

import com.promptvidya.trustdesk.domain.AccessRequestRecord.RequestState;
import com.promptvidya.trustdesk.domain.Ticket.TicketState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory domain services with seed data: enough business for the
 * agent to protect, small enough to read in one sitting. Every mutation
 * returns the new record, so callers can emit evidence for it.
 */
public final class TrustDeskDomain {

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, AccessRequestRecord> requests = new ConcurrentHashMap<>();
    private final Supplier<String> ids;

    public TrustDeskDomain(Supplier<String> ids) {
        this.ids = Objects.requireNonNull(ids);
    }

    public static TrustDeskDomain seeded() {
        var counter = new java.util.concurrent.atomic.AtomicInteger(100);
        var domain = new TrustDeskDomain(() -> "T-" + counter.incrementAndGet());
        domain.tickets.put("T-1", Ticket.open("T-1", "alice", "VPN drops every hour"));
        domain.tickets.put("T-2", Ticket.open("T-2", "bob", "Payroll export access"));
        domain.requests.put("R-1", AccessRequestRecord.pending(
                "R-1", "alice", "REPORT_VIEWER", "quarterly reporting"));
        return domain;
    }

    public List<Ticket> ticketsOwnedBy(String subject) {
        return tickets.values().stream()
                .filter(ticket -> ticket.owner().equals(subject))
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .toList();
    }

    public Optional<Ticket> ticket(String id) {
        return Optional.ofNullable(tickets.get(id));
    }

    public Ticket openTicket(String owner, String summary) {
        var ticket = Ticket.open(ids.get(), owner, summary);
        tickets.put(ticket.id(), ticket);
        return ticket;
    }

    public Ticket moveTicket(String id, TicketState next, String actor) {
        return tickets.compute(id, (key, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("unknown ticket");
            }
            return current.moveTo(next, actor);
        });
    }

    public AccessRequestRecord requestAccess(String subject, String entitlement, String justification) {
        var request = AccessRequestRecord.pending(ids.get(), subject, entitlement, justification);
        requests.put(request.id(), request);
        return request;
    }

    public AccessRequestRecord decide(String requestId, boolean approved, String decider) {
        return requests.compute(requestId, (key, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("unknown request");
            }
            return current.decide(approved, decider);
        });
    }

    public List<AccessRequestRecord> pendingRequests() {
        return requests.values().stream()
                .filter(request -> request.state() == RequestState.PENDING_APPROVAL)
                .toList();
    }
}
