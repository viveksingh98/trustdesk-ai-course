package com.promptvidya.trustdesk.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns on {@code @PreAuthorize} evaluation for every Spring bean. Kept
 * separate from the web configuration on purpose: the service-layer
 * gate must exist even for callers that never pass through HTTP.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfiguration {}
