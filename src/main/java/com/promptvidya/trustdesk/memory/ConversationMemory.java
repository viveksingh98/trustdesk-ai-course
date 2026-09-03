package com.promptvidya.trustdesk.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user conversation history for TrustDesk.
 *
 * <p>Isolation rule: every read and write is keyed by the authenticated
 * subject, so one user's turns can never appear in another user's window.
 * Retention rule: a hard cap trims the oldest turns first, keeping the
 * resent history inside the token budget.
 */
public final class ConversationMemory {

    public record Turn(String userText, String assistantText) {
        public Turn {
            Objects.requireNonNull(userText, "userText must not be null");
            Objects.requireNonNull(assistantText, "assistantText must not be null");
        }
    }

    private final int maximumTurnsPerSubject;
    private final Map<String, Deque<Turn>> turnsBySubject = new ConcurrentHashMap<>();

    public ConversationMemory(int maximumTurnsPerSubject) {
        if (maximumTurnsPerSubject < 1) {
            throw new IllegalArgumentException("maximumTurnsPerSubject must be positive");
        }
        this.maximumTurnsPerSubject = maximumTurnsPerSubject;
    }

    /** Returns only the calling subject's turns, oldest first. */
    public List<Turn> history(String subject) {
        var turns = turnsBySubject.get(requireSubject(subject));
        return turns == null ? List.of() : List.copyOf(turns);
    }

    /** Appends one completed turn for the subject, trimming the oldest at the cap. */
    public void append(String subject, Turn turn) {
        Objects.requireNonNull(turn, "turn must not be null");
        turnsBySubject.compute(requireSubject(subject), (key, existing) -> {
            var turns = existing == null ? new ArrayDeque<Turn>() : existing;
            turns.addLast(turn);
            while (turns.size() > maximumTurnsPerSubject) {
                turns.removeFirst();
            }
            return turns;
        });
    }

    private static String requireSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must be a nonblank authenticated name");
        }
        return subject;
    }
}
