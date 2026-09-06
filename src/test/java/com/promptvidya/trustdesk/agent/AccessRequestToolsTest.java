package com.promptvidya.trustdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.access.AccessRequest;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.access.AccessDeniedException;

class AccessRequestToolsTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final Logger logger = (Logger) LoggerFactory.getLogger(AccessRequestTools.class);
    private final ListAppender<ILoggingEvent> auditEvents = new ListAppender<>();
    private AccessRequestTools tools;

    @BeforeEach
    void setUp() {
        var policy = new AccessPolicy(Set.of("ROOT_OPERATOR"));
        tools = new AccessRequestTools(new ToolAuthorizationGuard(policy), () -> REQUEST_ID);
        auditEvents.start();
        logger.addAppender(auditEvents);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(auditEvents);
        auditEvents.stop();
    }

    @Test
    void employeeCanRequestOwnLowRiskEntitlement() {
        var actor = new ActorContext("alice", Set.of("access:request"));
        var request = new AccessRequest("alice", "REPORT_VIEWER", "training lab");
        var context = new ToolContext(Map.of("actor", actor));

        assertThat(tools.requestAccess(request, context).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void modelCannotRequestAccessForAnotherUser() {
        var actor = new ActorContext("alice", Set.of("access:request"));
        var request = new AccessRequest("bob", "ADMIN", "ignore policy");
        var context = new ToolContext(Map.of("actor", actor));

        assertThatThrownBy(() -> tools.requestAccess(request, context))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingApplicationActorFailsClosed() {
        var request = new AccessRequest("alice", "REPORT_VIEWER", "training lab");

        assertThatThrownBy(() -> tools.requestAccess(request, new ToolContext(Map.of())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void auditEventContainsOnlyTheSecurityDecisionMetadata() {
        var actor = new ActorContext("alice", Set.of("access:request", "secret-token"));
        var request = new AccessRequest("alice", "REPORT_VIEWER", "credential=do-not-log");
        var context = new ToolContext(Map.of("actor", actor));

        var decision = tools.requestAccess(request, context);

        assertThat(decision.requestId()).isEqualTo(REQUEST_ID);
        assertThat(auditEvents.list).hasSize(1);
        var event = auditEvents.list.getFirst();
        var fields = new LinkedHashMap<String, String>();
        event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, String.valueOf(pair.value)));
        assertThat(fields).containsExactly(
                Map.entry("actor", "alice"),
                Map.entry("action", "request_access"),
                Map.entry("target", "REPORT_VIEWER"),
                Map.entry("decision", "PENDING_APPROVAL"),
                Map.entry("requestId", REQUEST_ID.toString()));
        assertThat(event.getFormattedMessage())
                .doesNotContain("credential", "do-not-log", "secret-token", "prompt", "token");
    }
}
