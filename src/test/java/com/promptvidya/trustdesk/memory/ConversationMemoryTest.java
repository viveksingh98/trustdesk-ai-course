package com.promptvidya.trustdesk.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.promptvidya.trustdesk.memory.ConversationMemory.Turn;
import org.junit.jupiter.api.Test;

class ConversationMemoryTest {

    @Test
    void subjectsNeverSeeEachOthersHistory() {
        var memory = new ConversationMemory(10);
        memory.append("alice", new Turn("reset my token", "Use the self-service portal."));
        memory.append("bob", new Turn("open a ticket", "Ticket TD-7 created."));

        assertThat(memory.history("alice"))
                .containsExactly(new Turn("reset my token", "Use the self-service portal."));
        assertThat(memory.history("bob"))
                .containsExactly(new Turn("open a ticket", "Ticket TD-7 created."));
        assertThat(memory.history("carol")).isEmpty();
    }

    @Test
    void oldestTurnsTrimFirstAtTheCap() {
        var memory = new ConversationMemory(2);
        memory.append("alice", new Turn("one", "1"));
        memory.append("alice", new Turn("two", "2"));
        memory.append("alice", new Turn("three", "3"));

        assertThat(memory.history("alice"))
                .containsExactly(new Turn("two", "2"), new Turn("three", "3"));
    }

    @Test
    void blankSubjectsAreRejected() {
        var memory = new ConversationMemory(2);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> memory.history(" "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> memory.append("", new Turn("x", "y")));
    }
}
