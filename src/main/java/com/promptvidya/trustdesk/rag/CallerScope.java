package com.promptvidya.trustdesk.rag;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns identity into retrieval scope. The readable owners come from the
 * actor's scopes — never from the question, never from an argument the
 * model fills — so the metadata filter expresses who is asking.
 *
 * <p>Scope grammar: {@code policies:<owner>} grants one owning team's
 * shelf. Anything else is ignored; nothing is inferred; an actor with no
 * policy scopes retrieves nothing at all. Every scoped answer leaves an
 * audit event naming the shelves that were searched.
 */
public final class CallerScope {

    static final String POLICY_SCOPE_PREFIX = "policies:";
    static final String RETRIEVE_ACTION = "retrieve_policies";
    private static final Pattern SAFE_OWNER = Pattern.compile("[a-z0-9-]{1,64}");

    private final CitedAnswerService answers;
    private final AuditTrail audit;

    public CallerScope(CitedAnswerService answers, AuditTrail audit) {
        this.answers = Objects.requireNonNull(answers);
        this.audit = Objects.requireNonNull(audit);
    }

    /** Scopes to owners: keep the policy prefix, strip it, validate what remains. */
    public static Set<String> readableOwnersFor(ActorContext actor) {
        Objects.requireNonNull(actor);
        return actor.scopes().stream()
                .filter(scope -> scope.startsWith(POLICY_SCOPE_PREFIX))
                .map(scope -> scope.substring(POLICY_SCOPE_PREFIX.length()))
                .filter(owner -> SAFE_OWNER.matcher(owner).matches())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Answer for an actor: scope derived above, evidence recorded, question never in the room. */
    public CitedAnswerService.CitedAnswer answerFor(String question, ActorContext actor) {
        var owners = readableOwnersFor(actor);
        var outcome = owners.isEmpty() ? "EMPTY_SCOPE" : "ALLOWED";
        audit.record(actor.subject(), RETRIEVE_ACTION, shelves(owners), outcome);
        return answers.answer(question, owners);
    }

    private static String shelves(Set<String> owners) {
        return owners.isEmpty() ? "none" : String.join(",", new TreeSet<>(owners));
    }
}
