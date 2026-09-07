package com.promptvidya.trustdesk.evaluation;

import com.promptvidya.trustdesk.evaluation.AnswerScorer.Score;
import com.promptvidya.trustdesk.rag.CitedAnswerService.CitedAnswer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Runs a golden set through whatever answers questions — the real RAG
 * service, or a scripted stand-in — and turns the scores into one
 * number with a threshold. A case whose answerer throws scores zero
 * and names the exception type, never its message; the report lines
 * carry ids and check names only, so a poisoned answer cannot ride the
 * report into a dashboard.
 */
public final class EvaluationHarness {

    public record Report(List<Score> scores, double suiteRatio, double threshold, List<String> lines) {
        public Report {
            scores = List.copyOf(scores);
            lines = List.copyOf(lines);
        }

        public boolean passed() {
            return suiteRatio >= threshold;
        }

        /** How far this run fell from a previous one; positive means worse. */
        public double dropFrom(Report previous) {
            return previous.suiteRatio() - suiteRatio;
        }
    }

    private final AnswerScorer scorer;
    private final double threshold;

    public EvaluationHarness(AnswerScorer scorer, double threshold) {
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold is a ratio between 0 and 1");
        }
        this.scorer = Objects.requireNonNull(scorer);
        this.threshold = threshold;
    }

    public Report evaluate(List<GoldenCase> goldenSet, Function<GoldenCase, CitedAnswer> answerer) {
        if (goldenSet.isEmpty()) {
            throw new IllegalArgumentException("a golden set has at least one case");
        }
        var scores = new ArrayList<Score>();
        var lines = new ArrayList<String>();
        for (var golden : goldenSet) {
            Score score;
            try {
                score = scorer.score(golden, answerer.apply(golden));
            } catch (RuntimeException failure) {
                score = new Score(golden.id(), 0, AnswerScorer.CHECKS,
                        List.of("answerer-failed:" + failure.getClass().getSimpleName()));
            }
            scores.add(score);
            lines.add(score.caseId() + " " + score.points() + "/" + score.maximum()
                    + (score.findings().isEmpty() ? "" : " " + String.join(",", score.findings())));
        }
        var earned = scores.stream().mapToInt(Score::points).sum();
        var possible = scores.stream().mapToInt(Score::maximum).sum();
        var ratio = (double) earned / possible;
        lines.add(String.format("suite %.2f threshold %.2f %s", ratio, threshold, ratio >= threshold ? "PASS" : "FAIL"));
        return new Report(scores, ratio, threshold, lines);
    }
}
