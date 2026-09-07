package com.promptvidya.trustdesk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.access.AccessRequest;
import com.promptvidya.trustdesk.authz.DecisionLayer;
import com.promptvidya.trustdesk.authz.DecisionLayer.Outcome;
import com.promptvidya.trustdesk.authz.ToolPolicy;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.hardening.TrustedContext;
import com.promptvidya.trustdesk.hardening.UntrustedText;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import com.promptvidya.trustdesk.testing.AttackCorpus.Attack;
import com.promptvidya.trustdesk.testing.AttackCorpus.Gate;
import java.text.Normalizer;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;

/**
 * The regression suite: the whole corpus against every gate, on every
 * change. Structural checks run for every attack — fenced without
 * tags or invisible characters, never inside the system message,
 * capped when repeated to flood the window — and each attack's named
 * gate must stop it the way it did the first time.
 */
class SecurityRegressionSuiteTest {

    /** Any tag but our own fence, and any character the fence promises to strip. */
    private static final Pattern FOREIGN_TAG = Pattern.compile("<(?!/?untrusted[ >])[A-Za-z/!?]");
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\p{Cc}&&[^\\t\\n]]|[\\u200B-\\u200F\\u2028-\\u202E\\u2060-\\u2064\\uFEFF]");
    private static final String SYSTEM_FOR_ALICE = TrustedContext.INSTRUCTIONS + "\n\nEvidence:\nsubject: alice";

    private final ActorContext alice = new ActorContext("alice", Set.of("tickets:read", "access:request"));
    private final DecisionLayer decisions = new DecisionLayer(ToolPolicy.trustDesk(), new AuditTrail(FrozenClock.at("2026-09-07T10:00:00Z")));
    private final ToolAuthorizationGuard guard = new ToolAuthorizationGuard(new AccessPolicy(Set.of("ROOT_OPERATOR")));

    static Stream<Attack> corpus() {
        return AttackCorpus.load().stream();
    }

    @Test
    void theCorpusOnlyGrowsAndEveryIdIsUnique() {
        var attacks = AttackCorpus.load();
        assertThat(attacks).hasSizeGreaterThanOrEqualTo(14);
        assertThat(attacks).extracting(Attack::id).doesNotHaveDuplicates();
        assertThat(attacks).extracting(Attack::gate).contains(Gate.FENCE, Gate.PROMPT, Gate.TOOL, Gate.GUARD);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void everyAttackIsFencedWithoutForeignTagsOrInvisibleCharacters(Attack attack) {
        var fenced = UntrustedText.fence("document", "policy", attack.payload());

        assertThat(fenced).startsWith("<untrusted ").endsWith("</untrusted>");
        assertThat(fenced.indexOf("</untrusted>")).isEqualTo(fenced.lastIndexOf("</untrusted>"));
        assertThat(FOREIGN_TAG.matcher(fenced).find()).as("no foreign tag survives: %s", attack).isFalse();
        assertThat(INVISIBLE.matcher(fenced).find()).as("no invisible character survives: %s", attack).isFalse();
        assertThat(Normalizer.isNormalized(fenced, Normalizer.Form.NFKC)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void everyAttackStaysOutOfTheSystemMessage(Attack attack) {
        var messages = TrustedContext.forSubject("alice")
                .untrusted("document", "policy", attack.payload())
                .assemble("What does the policy say?")
                .getInstructions();

        assertThat(messages.getFirst().getText()).isEqualTo(SYSTEM_FOR_ALICE);
        assertThat(messages.getLast().getText()).contains("<untrusted source=\"document\"");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void everyAttackIsCappedWhenRepeatedToFloodTheWindow(Attack attack) {
        var flood = attack.payload().repeat(400);

        var sanitized = UntrustedText.sanitize(flood);

        assertThat(sanitized.length()).isLessThan(flood.length());
        assertThat(sanitized).endsWith("[truncated by input hardening]");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void theNamedGateStopsEachAttackTheWayItDidTheFirstTime(Attack attack) {
        switch (attack.gate()) {
            case FENCE -> assertThat(UntrustedText.sanitize(attack.payload()))
                    .doesNotContain("<script", "</untrusted>", "<untrusted");
            case PROMPT -> assertThat(TrustedContext.forSubject("alice")
                            .history(List.of())
                            .assemble(attack.payload())
                            .getInstructions().getFirst().getText())
                    .isEqualTo(SYSTEM_FOR_ALICE);
            case TOOL -> assertThat(decisions.decide(alice, attack.argument(), attack.payload()).outcome())
                    .isEqualTo(Outcome.DENIED_UNKNOWN_TOOL);
            case GUARD -> assertThatThrownBy(() -> guard.authorize(
                            alice, new AccessRequest(attack.argument(), "ADMIN", attack.payload())))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }
}
