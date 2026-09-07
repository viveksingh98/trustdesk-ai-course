package com.promptvidya.trustdesk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import io.modelcontextprotocol.server.McpSyncServer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * The capabilities as the MCP server will publish them: two tools with
 * data-only schemas, ownership and shelf enforced against the
 * authenticated caller, evidence per outcome, and no caller means no
 * capability at all.
 */
@SpringBootTest
class TrustDeskCapabilitiesTest {

    @Autowired
    private ToolCallbackProvider trustDeskMcpTools;

    @Autowired
    private TrustDeskCapabilities capabilities;

    @Autowired
    private AuditTrail audit;

    @Autowired
    private McpSyncServer server;

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}")
    private String endpoint;

    @Test
    void exactlyTwoCapabilitiesWithDataOnlySchemas() {
        var callbacks = trustDeskMcpTools.getToolCallbacks();

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("ticket_by_id", "policy_article");
        assertThat(Arrays.stream(callbacks).map(ToolCallback::getToolDefinition).map(definition -> definition.inputSchema()))
                .allSatisfy(schema -> assertThat(schema)
                        .doesNotContain("actor", "subject", "scopes", "credentials"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "EMPLOYEE")
    void ownersReadTheirTicketsAndNobodyElses() {
        assertThat(capabilities.ticketById("T-1")).contains("T-1").contains("VPN");

        assertThatThrownBy(() -> capabilities.ticketById("T-2")).isInstanceOf(AccessDeniedException.class);
        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(TrustDeskCapabilities.READ_TICKET, "T-1", "ALLOWED"),
                        org.assertj.core.groups.Tuple.tuple(TrustDeskCapabilities.READ_TICKET, "T-2", "REFUSED"));
    }

    @Test
    @WithMockUser(username = "alice", authorities = {"ROLE_EMPLOYEE", "policies:it-hardware"})
    void policiesFollowTheCallersShelf() {
        assertThat(capabilities.policyArticle("laptop-refresh")).contains("owner=it-hardware");

        assertThatThrownBy(() -> capabilities.policyArticle("payroll-export-access"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noCallerMeansNoCapability() {
        assertThatThrownBy(() -> capabilities.ticketById("T-1")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theServerPublishesOnTheStreamableEndpoint() {
        assertThat(server).isNotNull();
        assertThat(endpoint).isEqualTo("/mcp");
    }
}
