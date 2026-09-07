package com.promptvidya.trustdesk.api;

import com.promptvidya.trustdesk.memory.ConversationMemory;
import com.promptvidya.trustdesk.prompt.SupportPromptTemplate;
import com.promptvidya.trustdesk.resilience.GuardedModelCall;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the shipping chat endpoint: bounded per-user memory and the
 * failure guard, with limits declared in one visible place.
 */
@Configuration
public class ChatEndpointConfiguration {

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
