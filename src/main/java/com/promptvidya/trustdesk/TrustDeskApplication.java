package com.promptvidya.trustdesk;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.agent.AccessRequestTools;
import com.promptvidya.trustdesk.agent.AgentChatService;
import com.promptvidya.trustdesk.security.ToolAuthorizationGuard;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TrustDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrustDeskApplication.class, args);
    }

    @Bean
    AccessPolicy accessPolicy() {
        return new AccessPolicy(Set.of("ROOT_OPERATOR"));
    }

    @Bean
    ToolAuthorizationGuard toolAuthorizationGuard(AccessPolicy policy) {
        return new ToolAuthorizationGuard(policy);
    }

    @Bean
    Supplier<UUID> requestIdSupplier() {
        return UUID::randomUUID;
    }

    @Bean
    AccessRequestTools accessRequestTools(ToolAuthorizationGuard guard, Supplier<UUID> requestIdSupplier) {
        return new AccessRequestTools(guard, requestIdSupplier);
    }

    @Bean
    AgentChatService agentChatService(ChatClient.Builder chatClientBuilder, AccessRequestTools tools) {
        return new AgentChatService(chatClientBuilder.build(), tools);
    }
}
