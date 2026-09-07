package com.promptvidya.trustdesk.domain;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The in-memory domain as beans: one seeded domain, one seeded knowledge
 * base, and one append-only audit trail shared by every guarded service.
 */
@Configuration
public class TrustDeskDomainConfiguration {

    @Bean
    Clock trustDeskClock() {
        return Clock.systemUTC();
    }

    @Bean
    TrustDeskDomain trustDeskDomain() {
        return TrustDeskDomain.seeded();
    }

    @Bean
    KnowledgeBase knowledgeBase() {
        return KnowledgeBase.seeded();
    }

    @Bean
    AuditTrail auditTrail(Clock clock) {
        return new AuditTrail(clock);
    }
}
