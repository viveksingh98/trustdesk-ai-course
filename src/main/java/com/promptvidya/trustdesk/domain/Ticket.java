package com.promptvidya.trustdesk.domain;

import java.util.List;
import java.util.Objects;

/**
 * A support ticket. Ownership is a stored fact and every state change
 * appends to the history, so a ticket can always be audited.
 */
public record Ticket(String id, String owner, String summary, TicketState state, List<String> history) {

    public enum TicketState { OPEN, IN_PROGRESS, CLOSED }

    public Ticket {
        Objects.requireNonNull(id);
        Objects.requireNonNull(state);
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("ticket owner must not be blank");
        }
        history = List.copyOf(history);
    }

    public static Ticket open(String id, String owner, String summary) {
        return new Ticket(id, owner, summary, TicketState.OPEN, List.of("opened by " + owner));
    }

    public Ticket moveTo(TicketState next, String actor) {
        if (state == TicketState.CLOSED) {
            throw new IllegalStateException("closed tickets do not change state");
        }
        var entry = state + " -> " + next + " by " + actor;
        var updated = new java.util.ArrayList<>(history);
        updated.add(entry);
        return new Ticket(id, owner, summary, next, updated);
    }
}
