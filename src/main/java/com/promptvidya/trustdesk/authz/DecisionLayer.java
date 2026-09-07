package com.promptvidya.trustdesk.authz;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.util.Objects;

/**
 * The decision before every tool run. Subject and scopes come from the
 * actor the transport produced; the tool's requirement comes from the
 * policy; intent is the caller's stated reason — evidence, never
 * authority. Every decision is recorded before anything runs, and the
 * recorded target is the tool name, not the intent text: the trail
 * stores references, never untrusted content.
 */
public final class DecisionLayer {

    public enum Outcome { ALLOWED, DENIED_UNKNOWN_TOOL, DENIED_NO_GRANT, DENIED_NO_INTENT }

    public record Decision(Outcome outcome, String tool) {
        public boolean allowed() {
            return outcome == Outcome.ALLOWED;
        }
    }

    static final String ACTION = "tool_run";
    static final int MAXIMUM_INTENT_CHARACTERS = 500;

    private final ToolPolicy policy;
    private final AuditTrail audit;

    public DecisionLayer(ToolPolicy policy, AuditTrail audit) {
        this.policy = Objects.requireNonNull(policy);
        this.audit = Objects.requireNonNull(audit);
    }

    public Decision decide(ActorContext actor, String tool, String intent) {
        return decide(actor, tool, intent, null);
    }

    /** With a delegation chain the evidence names every hop: tool@person via agent via server. */
    public Decision decide(ActorContext actor, String tool, String intent, DelegationChain chain) {
        Objects.requireNonNull(actor, "no actor, no decision");
        var requirement = policy.requirementFor(tool).orElse(null);
        Outcome outcome;
        if (requirement == null) {
            outcome = Outcome.DENIED_UNKNOWN_TOOL;
        } else if (!requirement.satisfiedBy(actor.scopes())) {
            outcome = Outcome.DENIED_NO_GRANT;
        } else if (requirement.sensitive() && !hasIntent(intent)) {
            outcome = Outcome.DENIED_NO_INTENT;
        } else {
            outcome = Outcome.ALLOWED;
        }
        var target = chain == null ? tool : chain.reference(tool);
        audit.record(actor.subject(), ACTION, target, outcome.name());
        return new Decision(outcome, tool);
    }

    /** Present and bounded — long intents are as suspicious as missing ones. */
    static boolean hasIntent(String intent) {
        return intent != null && !intent.isBlank() && intent.length() <= MAXIMUM_INTENT_CHARACTERS;
    }
}
