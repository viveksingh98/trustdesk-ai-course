package com.promptvidya.trustdesk.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The agent's door: a second filter chain for {@code /api/**} that never
 * creates a session, never redirects to a login page, and admits only a
 * bearer JWT the {@link DevelopmentJwtKeys} decoder accepts.
 *
 * <p>The token's scope claim becomes the caller's authorities verbatim —
 * the same scope strings the tool chain already consumes — so a token
 * for the API and a session for the web app produce the same kind of
 * actor downstream.
 */
@Configuration
public class AgentApiSecurityConfiguration {

    static final String API_PATTERN = "/api/**";
    static final String CHAT_SCOPE = "agent:chat";

    @Bean
    @Order(1)
    SecurityFilterChain agentApiFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(API_PATTERN)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/chat").hasAuthority(CHAT_SCOPE)
                        .anyRequest().authenticated())
                .sessionManagement(sessions -> sessions
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(scopesAsAuthorities())))
                .build();
    }

    /** No SCOPE_ prefix: "policies:it-hardware" in the token is "policies:it-hardware" here. */
    static JwtAuthenticationConverter scopesAsAuthorities() {
        var scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthorityPrefix("");
        scopes.setAuthoritiesClaimName("scope");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(scopes);
        return converter;
    }
}
