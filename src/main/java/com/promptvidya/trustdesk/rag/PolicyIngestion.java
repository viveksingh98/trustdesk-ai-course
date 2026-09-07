package com.promptvidya.trustdesk.rag;

import com.promptvidya.trustdesk.domain.KnowledgeBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Ingestion is admission control. Every chunk that enters the store can
 * end up in the window, so each one carries the article's owner, slug,
 * and title as metadata — provenance survives the split — and chunks
 * never cut a sentence, so a rule cannot become its opposite.
 */
public final class PolicyIngestion {

    static final int MAXIMUM_CHUNK_CHARACTERS = 240;

    private final VectorStore store;

    public PolicyIngestion(VectorStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public List<Document> ingest(KnowledgeBase knowledge) {
        var documents = new ArrayList<Document>();
        for (var article : knowledge.all()) {
            var chunks = chunkBySentence(article.body(), MAXIMUM_CHUNK_CHARACTERS);
            for (int index = 0; index < chunks.size(); index++) {
                documents.add(Document.builder()
                        .id(article.slug() + "#" + index)
                        .text(chunks.get(index))
                        .metadata(Map.of(
                                "slug", article.slug(),
                                "owner", article.owner(),
                                "title", article.title(),
                                "chunk", index))
                        .build());
            }
        }
        store.add(documents);
        return List.copyOf(documents);
    }

    /**
     * Groups whole sentences up to the character budget. A single sentence
     * longer than the budget stays whole: cutting mid-sentence is the one
     * thing a chunker must never do to a policy.
     */
    static List<String> chunkBySentence(String body, int maximumCharacters) {
        var sentences = body.trim().split("(?<=[.!?])\\s+");
        var chunks = new ArrayList<String>();
        var current = new StringBuilder();
        for (var sentence : sentences) {
            if (current.length() > 0 && current.length() + 1 + sentence.length() > maximumCharacters) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return List.copyOf(chunks);
    }
}
