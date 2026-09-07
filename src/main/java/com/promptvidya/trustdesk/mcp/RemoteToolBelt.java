package com.promptvidya.trustdesk.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.security.access.AccessDeniedException;

/**
 * The agent's side of MCP: one authenticated connection to one server,
 * a fixed allowlist of the tools we expect (a server that advertises
 * more does not get to enlarge the belt), and every result fenced as
 * untrusted data before the model sees it.
 */
public final class RemoteToolBelt implements AutoCloseable {

    static final Set<String> ALLOWED_TOOLS = Set.of("ticket_by_id", "policy_article");
    static final String SOURCE = "trustdesk-mcp";
    static final int MAXIMUM_CHARACTERS = 4000;

    private final McpSyncClient client;

    private RemoteToolBelt(McpSyncClient client) {
        this.client = Objects.requireNonNull(client);
    }

    /** Connect with a bearer token on every request; initialize fails closed if the door refuses. */
    public static RemoteToolBelt connect(String baseUrl, String endpoint, String bearerToken, Duration timeout) {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(endpoint)
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + bearerToken))
                .build();
        var client = McpClient.sync(transport)
                .clientInfo(new Implementation("trustdesk-agent", "1.0.0"))
                .requestTimeout(timeout)
                .build();
        client.initialize();
        return new RemoteToolBelt(client);
    }

    /** What the server advertises — informational; the belt is decided by ALLOWED_TOOLS, not by this list. */
    public List<String> advertisedTools() {
        return client.listTools().tools().stream().map(tool -> tool.name()).sorted().toList();
    }

    /** The remote tools as Spring AI callbacks: allowlisted, unprefixed, and fenced. */
    public ToolCallbackProvider asToolCallbacks() {
        var provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(List.of(client))
                .toolFilter((connection, tool) -> ALLOWED_TOOLS.contains(tool.name()))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .build();
        return () -> Arrays.stream(provider.getToolCallbacks())
                .map(FencedToolCallback::new)
                .toArray(ToolCallback[]::new);
    }

    /** Direct call, same rules: allowlist first, refusal surfaced, result fenced. */
    public String call(String tool, Map<String, Object> arguments) {
        if (!ALLOWED_TOOLS.contains(tool)) {
            throw new AccessDeniedException("tool is not on the belt: " + tool);
        }
        var result = client.callTool(new CallToolRequest(tool, arguments));
        var text = result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new AccessDeniedException("remote tool refused: " + tool);
        }
        return fence(tool, text);
    }

    /** Untrusted data, labeled and capped — the same discipline as documents and policy pages. */
    static String fence(String tool, String text) {
        var body = text.length() > MAXIMUM_CHARACTERS ? text.substring(0, MAXIMUM_CHARACTERS) : text;
        return "<tool_result source=\"" + SOURCE + "\" tool=\"" + tool + "\" trust=\"untrusted-data\">\n"
                + body + "\n</tool_result>";
    }

    @Override
    public void close() {
        client.closeGracefully();
    }

    /** Wraps a remote callback so the model only ever sees fenced text. */
    static final class FencedToolCallback implements ToolCallback {

        private final ToolCallback delegate;

        FencedToolCallback(ToolCallback delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return fence(delegate.getToolDefinition().name(), delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
            return fence(delegate.getToolDefinition().name(), delegate.call(toolInput, toolContext));
        }
    }
}
