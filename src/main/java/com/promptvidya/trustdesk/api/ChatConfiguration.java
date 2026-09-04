package com.promptvidya.trustdesk.api;

import com.promptvidya.trustdesk.chat.ChatService;
import com.promptvidya.trustdesk.memory.ConversationMemory;
import com.promptvidya.trustdesk.prompt.SupportPromptTemplate;
import com.promptvidya.trustdesk.resilience.GuardedModelCall;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the chat endpoint, with the limits declared in one place. */
@Configuration
public class ChatConfiguration {

    @Bean
    ChatService chatService(ChatClient.Builder chatClientBuilder) {
        return new ChatService(chatClientBuilder.build());
    }

    @Bean
    ConversationMemory conversationMemory() {
        return new ConversationMemory(20);
    }

    @Bean
    GuardedModelCall guardedModelCall() {
        return new GuardedModelCall(2, Duration.ofSeconds(20));
    }

    @Bean
    SupportPromptTemplate supportPromptTemplate() {
        return new SupportPromptTemplate();
    }
}
