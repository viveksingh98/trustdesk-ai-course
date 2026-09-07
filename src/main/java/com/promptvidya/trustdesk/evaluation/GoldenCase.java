package com.promptvidya.trustdesk.evaluation;

import java.util.Set;

/**
 * One question the system must keep answering well: the article it
 * should cite, whether it should refuse, and how long it may run on.
 * A golden set is a handful of these, reviewed by a person, kept in
 * the repository beside the prompt they evaluate.
 */
public record GoldenCase(
        String id, String question, Set<String> expectedSlugs, boolean expectRefusal, int maximumCharacters) {

    public GoldenCase {
        if (id == null || id.isBlank() || question == null || question.isBlank()) {
            throw new IllegalArgumentException("a golden case has an id and a question");
        }
        if (maximumCharacters <= 0) {
            throw new IllegalArgumentException("maximumCharacters must be positive");
        }
        expectedSlugs = Set.copyOf(expectedSlugs);
    }

    public static GoldenCase citing(String id, String question, String slug) {
        return new GoldenCase(id, question, Set.of(slug), false, 600);
    }

    public static GoldenCase refusing(String id, String question) {
        return new GoldenCase(id, question, Set.of(), true, 400);
    }
}
