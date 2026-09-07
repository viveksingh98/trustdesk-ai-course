package com.promptvidya.trustdesk.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.promptvidya.trustdesk.domain.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

class PolicyIngestionTest {

    private final SimpleVectorStore store =
            SimpleVectorStore.builder(new HashingEmbeddingModel()).build();
    private final PolicyIngestion ingestion = new PolicyIngestion(store);

    @Test
    void everyChunkCarriesItsOwnerAndSlug() {
        var documents = ingestion.ingest(KnowledgeBase.seeded());

        assertThat(documents).isNotEmpty().allSatisfy(document -> {
            assertThat(document.getMetadata().get("owner")).asString().isNotBlank();
            assertThat(document.getMetadata().get("slug")).asString().isNotBlank();
            assertThat(document.getId()).contains("#");
        });
    }

    @Test
    void chunksNeverCutASentence() {
        var body = "Laptops are replaced every three years. Exceptions need a manager. Ask the hardware team first.";

        var chunks = PolicyIngestion.chunkBySentence(body, 60);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).endsWith("."));
        assertThat(String.join(" ", chunks)).isEqualTo(body);
    }

    @Test
    void anOversizedSentenceStaysWholeRatherThanCut() {
        var longSentence = "Payroll exports require the REPORT_VIEWER entitlement and a manager approval before any file leaves the finance system.";

        assertThat(PolicyIngestion.chunkBySentence(longSentence, 40)).containsExactly(longSentence);
    }

    @Test
    void theStoreFiltersByMetadataNotJustSimilarity() {
        ingestion.ingest(KnowledgeBase.seeded());

        var results = store.similaritySearch(SearchRequest.builder()
                .query("laptop replacement policy")
                .topK(5)
                .filterExpression("slug == 'payroll-export-access'")
                .build());

        assertThat(results).isNotEmpty()
                .allSatisfy(document -> assertThat(document.getMetadata().get("slug"))
                        .isEqualTo("payroll-export-access"));
    }
}
