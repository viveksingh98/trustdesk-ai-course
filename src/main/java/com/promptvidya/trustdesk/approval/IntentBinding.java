package com.promptvidya.trustdesk.approval;

import com.promptvidya.trustdesk.approval.ApprovalQueue.PendingAction;
import com.promptvidya.trustdesk.approval.ApprovalQueue.State;
import com.promptvidya.trustdesk.authz.DelegationChain;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.hardening.UntrustedText;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

/**
 * An approval is for exactly one proposal. The arguments are
 * canonicalized before they are digested, so two spellings of the same
 * JSON are one proposal and a changed amount is a different one; the
 * chain must match, so one person's approval cannot be spent by another
 * person's agent; and an approval is fresh for a short window after the
 * decision — after that, the proposal must be re-authorized, because
 * the world the person looked at has moved on.
 */
public final class IntentBinding {

    static final Duration FRESHNESS = Duration.ofMinutes(10);
    static final String SUBSTITUTION = "approval_substitution";
    static final String STALE = "approval_stale";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ApprovalQueue queue;
    private final AuditTrail audit;
    private final Clock clock;

    public IntentBinding(ApprovalQueue queue, AuditTrail audit, Clock clock) {
        this.queue = Objects.requireNonNull(queue);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Keys sorted, whitespace gone, nested objects too — the same meaning digests the same. */
    public static String canonical(String argumentsJson) {
        var tree = MAPPER.readValue(Objects.requireNonNullElse(argumentsJson, "{}"), Object.class);
        return MAPPER.writeValueAsString(sorted(tree));
    }

    public static String digestOf(String tool, String argumentsJson) {
        return UntrustedText.digest(tool + "\n" + canonical(argumentsJson));
    }

    /** Pause, bound: the queue stores the digest of the canonical form. */
    public PendingAction submit(ActorContext actor, DelegationChain chain, String tool, String argumentsJson, String intent) {
        return queue.submit(actor, chain, tool, canonical(argumentsJson), intent);
    }

    /** Proceed only if what is about to run is what was approved, by whom it was approved for, recently enough. */
    public PendingAction execute(String id, DelegationChain chain, String tool, String argumentsJson) {
        var action = queue.get(id).orElseThrow(() -> new AccessDeniedException("no such approval: " + id));
        if (!action.tool().equals(tool) || !action.argumentsDigest().equals(digestOf(tool, argumentsJson))
                || !action.chain().equals(chain.describe())) {
            audit.record(chain.subject(), SUBSTITUTION, id, "REFUSED");
            throw new AccessDeniedException("the approval is for a different proposal: " + id);
        }
        if (action.state() == State.APPROVED && action.decidedAt().plus(FRESHNESS).isBefore(clock.instant())) {
            audit.record(chain.subject(), STALE, id, "REAUTHORIZATION_REQUIRED");
            throw new AccessDeniedException("the approval is no longer fresh; propose again: " + id);
        }
        return queue.markExecuted(id);
    }

    @SuppressWarnings("unchecked")
    private static Object sorted(Object node) {
        if (node instanceof Map<?, ?> map) {
            var ordered = new TreeMap<String, Object>();
            map.forEach((key, value) -> ordered.put(String.valueOf(key), sorted(value)));
            return ordered;
        }
        if (node instanceof java.util.List<?> list) {
            return list.stream().map(IntentBinding::sorted).toList();
        }
        return node;
    }
}
