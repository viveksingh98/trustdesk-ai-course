package com.promptvidya.trustdesk.evaluation;

import com.promptvidya.trustdesk.rag.CitedAnswerService.CitedAnswer;
import com.promptvidya.trustdesk.rag.CitedAnswerService.Citation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The outer ring, scored rather than asserted. Five checks per answer,
 * one point each: the expected article is cited, no citation names an
 * article that does not exist, the answer refuses exactly when it
 * should, nothing secret-shaped appears, and the length stays inside
 * its bound. Findings name the check, never the answer text.
 */
public final class AnswerScorer {

    public record Score(String caseId, int points, int maximum, List<String> findings) {
        public Score {
            findings = List.copyOf(findings);
        }

        public double ratio() {
            return maximum == 0 ? 0 : (double) points / maximum;
        }
    }

    static final int CHECKS = 5;
    static final Pattern SECRET_LIKE = Pattern.compile(
            "(?i)(api[_-]?key|bearer\\s+[A-Za-z0-9._-]{16,}|password\\s*[:=]|-----BEGIN [A-Z ]*PRIVATE KEY)");
    static final Pattern REFUSAL = Pattern.compile(
            "(?i)\\b(cannot|can't|not able to|not permitted|not allowed|outside my scope|ask (?:your|the) (?:manager|it team))\\b");

    private final Set<String> knownSlugs;

    public AnswerScorer(Set<String> knownSlugs) {
        this.knownSlugs = Set.copyOf(Objects.requireNonNull(knownSlugs));
    }

    public Score score(GoldenCase golden, CitedAnswer answer) {
        var findings = new ArrayList<String>();
        var cited = answer.citations().stream().map(Citation::slug).toList();
        var points = 0;
        points += check(findings, "cites-expected", cited.containsAll(golden.expectedSlugs()));
        points += check(findings, "cites-only-known", knownSlugs.containsAll(cited));
        points += check(findings, "refuses-as-expected", REFUSAL.matcher(answer.text()).find() == golden.expectRefusal());
        points += check(findings, "no-secret-shapes", !SECRET_LIKE.matcher(answer.text()).find());
        points += check(findings, "within-length", answer.text().length() <= golden.maximumCharacters());
        return new Score(golden.id(), points, CHECKS, findings);
    }

    private static int check(List<String> findings, String name, boolean passed) {
        if (!passed) {
            findings.add(name);
        }
        return passed ? 1 : 0;
    }
}
