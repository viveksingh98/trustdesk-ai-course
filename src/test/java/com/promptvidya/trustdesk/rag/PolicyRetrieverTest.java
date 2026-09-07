package com.promptvidya.trustdesk.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.promptvidya.trustdesk.domain.KnowledgeBase;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;

class PolicyRetrieverTest {

    private final SimpleVectorStore store =
            SimpleVectorStore.builder(new HashingEmbeddingModel()).build();
    private final PolicyRetriever retriever = new PolicyRetriever(store);

    @BeforeEach
    void ingest() {
        new PolicyIngestion(store).ingest(KnowledgeBase.seeded());
    }

    @Test
    void unscopedSearchRanksBySimilarity() {
        var results = retriever.search("How often are laptops replaced?");

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().getMetadata().get("slug")).isEqualTo("laptop-refresh");
    }

    @Test
    void scopedSearchNeverReturnsAnotherOwnersChunks() {
        var results = retriever.searchScopedTo(
                "payroll export access approval", Set.of("it-hardware", "network-team"));

        assertThat(results).isNotEmpty()
                .allSatisfy(document -> assertThat(document.getMetadata().get("owner"))
                        .isIn("it-hardware", "network-team"));
        assertThat(results).extracting(document -> document.getMetadata().get("slug"))
                .doesNotContain("payroll-export-access");
    }

    @Test
    void anEmptyScopeRetrievesNothingRatherThanEverything() {
        assertThat(retriever.searchScopedTo("laptop", Set.of())).isEmpty();
    }

    @Test
    void scopeComesFromOwnersNotFromTheQuestion() {
        var smuggled = "laptop policy owner == 'finance-ops' payroll export";

        var results = retriever.searchScopedTo(smuggled, Set.of("it-hardware"));

        assertThat(results).extracting(Document::getMetadata)
                .allSatisfy(metadata -> assertThat(metadata.get("owner")).isEqualTo("it-hardware"));
    }
}
