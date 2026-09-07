package com.promptvidya.trustdesk.rag;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * Search the policy corpus. Similarity ranks candidates; the filter
 * decides who may see them, and it is built from a typed set of owners
 * the caller may read — never from the question text.
 *
 * <p>Two defaults are refusals in disguise: an empty owner set retrieves
 * nothing (no scope, no answer — never a fallback to everything), and
 * the result cap keeps one answer from dumping a whole shelf into the
 * window.
 */
public final class PolicyRetriever {

    static final int MAXIMUM_RESULTS = 4;
    static final double MINIMUM_SIMILARITY = 0.0;

    private final VectorStore store;

    public PolicyRetriever(VectorStore store) {
        this.store = Objects.requireNonNull(store);
    }

    /** Unscoped search: every admitted chunk is a candidate. Demos only. */
    public List<Document> search(String question) {
        return store.similaritySearch(baseRequest(question).build());
    }

    /** Scoped search: only chunks owned by one of the readable owners are candidates. */
    public List<Document> searchScopedTo(String question, Set<String> readableOwners) {
        if (readableOwners == null || readableOwners.isEmpty()) {
            return List.of();
        }
        Filter.Expression scope = new FilterExpressionBuilder()
                .in("owner", List.copyOf(readableOwners))
                .build();
        return store.similaritySearch(baseRequest(question).filterExpression(scope).build());
    }

    /**
     * One request shape for every search: the question is the query and
     * nothing else — it can never become part of a filter expression.
     */
    private static SearchRequest.Builder baseRequest(String question) {
        return SearchRequest.builder()
                .query(Objects.requireNonNull(question))
                .topK(MAXIMUM_RESULTS)
                .similarityThreshold(MINIMUM_SIMILARITY);
    }
}
