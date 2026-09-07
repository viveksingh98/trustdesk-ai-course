package com.promptvidya.trustdesk.mcp;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.KnowledgeBase;
import com.promptvidya.trustdesk.domain.TrustDeskDomain;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the capabilities to the MCP server. The Spring AI server
 * starter turns every ToolCallbackProvider bean into MCP tool
 * specifications; transport, protocol, and endpoint come from
 * application.yaml (streamable HTTP on /mcp, synchronous).
 */
@Configuration
public class TrustDeskMcpServerConfiguration {

    @Bean
    TrustDeskCapabilities trustDeskCapabilities(
            TrustDeskDomain domain, KnowledgeBase knowledge, AuditTrail audit) {
        return new TrustDeskCapabilities(domain, knowledge, audit);
    }

    @Bean
    ToolCallbackProvider trustDeskMcpTools(TrustDeskCapabilities capabilities) {
        return MethodToolCallbackProvider.builder().toolObjects(capabilities).build();
    }
}
