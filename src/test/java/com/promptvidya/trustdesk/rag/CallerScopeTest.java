package com.promptvidya.trustdesk.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.domain.KnowledgeBase;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.rag.CitedAnswerService.Citation;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SimpleVectorStore;

class CallerScopeTest {

    private final ChatModel model = new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("grounded answer"))));
        }
    };
    private final SimpleVectorStore store =
            SimpleVectorStore.builder(new HashingEmbeddingModel()).build();
    private final AuditTrail audit = new AuditTrail(Clock.systemUTC());
    private final CallerScope scope = new CallerScope(
            new CitedAnswerService(ChatClient.create(model), new PolicyRetriever(store)), audit);

    @BeforeEach
    void ingest() {
        new PolicyIngestion(store).ingest(KnowledgeBase.seeded());
    }

    @Test
    void policyScopesBecomeReadableOwners() {
        var actor = new ActorContext("alice", Set.of("tickets:read", "policies:it-hardware", "policies:network-team"));

        assertThat(CallerScope.readableOwnersFor(actor)).containsExactlyInAnyOrder("it-hardware", "network-team");
    }

    @Test
    void anActorWithoutPolicyScopesRetrievesNothingAndLeavesEvidence() {
        var actor = new ActorContext("bob", Set.of("tickets:read"));

        var answer = scope.answerFor("How do I export payroll?", actor);

        assertThat(answer.citations()).isEmpty();
        assertThat(audit.eventsFor("bob")).extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("retrieve_policies", "none", "EMPTY_SCOPE"));
    }

    @Test
    void malformedScopeValuesAreIgnoredNotInterpreted() {
        var actor = new ActorContext("mallory", Set.of("policies:finance-ops' OR owner != '", "policies:../admin"));

        assertThat(CallerScope.readableOwnersFor(actor)).isEmpty();
    }

    @Test
    void theShelfFollowsTheScopeNotTheQuestion() {
        var actor = new ActorContext("alice", Set.of("policies:it-hardware"));

        var answer = scope.answerFor("payroll export access approval", actor);

        assertThat(answer.citations()).isNotEmpty().extracting(Citation::owner).containsOnly("it-hardware");
        assertThat(audit.eventsFor("alice")).extracting(AuditEvent::target).containsExactly("it-hardware");
    }
}
