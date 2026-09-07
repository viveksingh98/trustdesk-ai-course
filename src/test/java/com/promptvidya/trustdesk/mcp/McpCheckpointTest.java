package com.promptvidya.trustdesk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import com.promptvidya.trustdesk.security.McpDoor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Section checkpoint: TrustDesk capabilities over secure MCP, every
 * strap at once — the door challenges and discovers, the belt is ours,
 * ownership and evidence survive the hop, the model only ever sees
 * fenced remote data, and another API's token opens nothing. The model
 * is the only fake; the transport is real.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class McpCheckpointTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private AuditTrail audit;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base() {
        return "http://localhost:" + port;
    }

    private String tokenFor(String subject, String audience) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(audience))
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .claim("scope", "agent:chat policies:it-hardware")
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private RemoteToolBelt beltFor(String subject) {
        return RemoteToolBelt.connect(
                base(), McpDoor.MCP_ENDPOINT, tokenFor(subject, McpDoor.MCP_AUDIENCE), Duration.ofSeconds(10));
    }

    @Test
    void strapOneTheDoorChallengesAndPointsAtItsMetadata() throws Exception {
        var challenge = http.send(
                HttpRequest.newBuilder(URI.create(base() + McpDoor.MCP_ENDPOINT)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        var metadata = http.send(
                HttpRequest.newBuilder(URI.create(base() + McpDoor.METADATA_PATH)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(challenge.statusCode()).isEqualTo(401);
        assertThat(challenge.headers().firstValue("WWW-Authenticate").orElse("")).contains("resource_metadata=");
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(metadata.body()).contains(McpDoor.RESOURCE).contains(DevelopmentJwtKeys.ISSUER);
    }

    @Test
    void strapTwoTheBeltIsDecidedByTheClientNotTheServer() {
        try (var belt = beltFor("alice")) {
            assertThat(belt.advertisedTools()).containsExactly("policy_article", "ticket_by_id");
            assertThat(belt.asToolCallbacks().getToolCallbacks())
                    .extracting(callback -> callback.getToolDefinition().name())
                    .containsExactlyInAnyOrder("policy_article", "ticket_by_id");
            assertThatThrownBy(() -> belt.call("delete_everything", Map.of()))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void strapThreeOwnershipAndEvidenceSurviveTheHop() {
        try (var belt = beltFor("alice")) {
            assertThat(belt.call("ticket_by_id", Map.of("ticketId", "T-1"))).contains("VPN");
            assertThatThrownBy(() -> belt.call("ticket_by_id", Map.of("ticketId", "T-2")))
                    .isInstanceOf(AccessDeniedException.class);
        }

        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .contains(
                        tuple(TrustDeskCapabilities.READ_TICKET, "T-1", "ALLOWED"),
                        tuple(TrustDeskCapabilities.READ_TICKET, "T-2", "REFUSED"));
    }

    @Test
    void strapFourTheModelOnlyEverSeesFencedRemoteData() {
        var calls = new AtomicInteger();
        var secondPrompt = new AtomicReference<Prompt>();
        // A scripted model must advertise tool-calling options: ChatClient seeds the prompt
        // from getOptions(), and without a ToolCallingChatOptions the callbacks are dropped.
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    var call = new AssistantMessage.ToolCall("call-1", "function", "ticket_by_id", "{\"ticketId\":\"T-1\"}");
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
                }
                secondPrompt.set(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Your VPN ticket is open."))));
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };

        var toolLoop = ToolCallingAdvisor.builder()
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();

        try (var belt = beltFor("alice")) {
            var answer = ChatClient.create(model)
                    .prompt()
                    .user("What is the state of my ticket?")
                    .toolCallbacks(belt.asToolCallbacks().getToolCallbacks())
                    .advisors(toolLoop)
                    .call()
                    .content();

            assertThat(answer).isEqualTo("Your VPN ticket is open.");
        }
        var toolResponse = secondPrompt.get().getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(toolResponse.getResponses().getFirst().responseData())
                .startsWith("<tool_result source=\"trustdesk-mcp\" tool=\"ticket_by_id\" trust=\"untrusted-data\">")
                .contains("VPN");
    }

    @Test
    void strapFiveAnotherApisTokenOpensNothingHere() {
        assertThatThrownBy(() -> RemoteToolBelt.connect(
                        base(), McpDoor.MCP_ENDPOINT, tokenFor("alice", DevelopmentJwtKeys.AUDIENCE), Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);
    }
}
