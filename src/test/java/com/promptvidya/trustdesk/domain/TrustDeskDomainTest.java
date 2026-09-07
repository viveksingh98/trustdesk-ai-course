package com.promptvidya.trustdesk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.promptvidya.trustdesk.domain.AccessRequestRecord.RequestState;
import com.promptvidya.trustdesk.domain.Ticket.TicketState;
import org.junit.jupiter.api.Test;

class TrustDeskDomainTest {

    private final TrustDeskDomain domain = TrustDeskDomain.seeded();

    @Test
    void ownershipIsAStoredFactOnEveryTicket() {
        assertThat(domain.ticketsOwnedBy("alice")).extracting(Ticket::id).containsExactly("T-1");
        assertThat(domain.ticketsOwnedBy("bob")).extracting(Ticket::id).containsExactly("T-2");
        assertThatIllegalArgumentException().isThrownBy(() -> Ticket.open("T-9", " ", "no owner"));
    }

    @Test
    void everyStateChangeAppendsHistory() {
        var moved = domain.moveTicket("T-1", TicketState.IN_PROGRESS, "helpdesk-bot");

        assertThat(moved.state()).isEqualTo(TicketState.IN_PROGRESS);
        assertThat(moved.history()).containsExactly(
                "opened by alice", "OPEN -> IN_PROGRESS by helpdesk-bot");
        assertThatIllegalStateException().isThrownBy(() ->
                domain.moveTicket("T-1", TicketState.CLOSED, "x").moveTo(TicketState.OPEN, "y"));
    }

    @Test
    void requestsAreNeverApprovedBySilence() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> domain.decide("R-1", true, " "))
                .withMessageContaining("decider");
        assertThat(domain.pendingRequests()).extracting(AccessRequestRecord::id).containsExactly("R-1");
    }

    @Test
    void onlyPendingRequestsCanBeDecidedAndTheDeciderIsRecorded() {
        var decided = domain.decide("R-1", true, "manager-priya");

        assertThat(decided.state()).isEqualTo(RequestState.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo("manager-priya");
        assertThatIllegalStateException().isThrownBy(() -> domain.decide("R-1", false, "again"));
    }

    @Test
    void newRecordsGetDomainIdsNotCallerIds() {
        var ticket = domain.openTicket("carol", "Monitor flickers");
        var request = domain.requestAccess("carol", "WIKI_EDITOR", "docs team");

        assertThat(ticket.id()).startsWith("T-");
        assertThat(request.id()).startsWith("T-");
        assertThat(domain.ticket(ticket.id())).isPresent();
    }
}
