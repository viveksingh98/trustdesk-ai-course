package com.promptvidya.trustdesk.domain;

import java.util.Objects;

/**
 * The domain's most sensitive object: it changes what someone may do.
 * Terminal states always name a decider — a request is never approved
 * by silence — and only a pending request can be decided.
 */
public record AccessRequestRecord(
        String id,
        String subject,
        String entitlement,
        String justification,
        RequestState state,
        String decidedBy) {

    public enum RequestState { PENDING_APPROVAL, APPROVED, DENIED }

    public AccessRequestRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(state);
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (state != RequestState.PENDING_APPROVAL && (decidedBy == null || decidedBy.isBlank())) {
            throw new IllegalArgumentException("a decided request must name its decider");
        }
    }

    public static AccessRequestRecord pending(
            String id, String subject, String entitlement, String justification) {
        return new AccessRequestRecord(
                id, subject, entitlement, justification, RequestState.PENDING_APPROVAL, null);
    }

    public AccessRequestRecord decide(boolean approved, String decider) {
        if (state != RequestState.PENDING_APPROVAL) {
            throw new IllegalStateException("only pending requests can be decided");
        }
        var next = approved ? RequestState.APPROVED : RequestState.DENIED;
        return new AccessRequestRecord(id, subject, entitlement, justification, next, decider);
    }
}
