package com.promptvidya.trustdesk.security;

import com.nimbusds.jose.JOSEException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The MCP server's own door. It is a resource server in its own right,
 * with its own identifier and its own audience — a token minted for the
 * agent API does not open it. Anonymous callers get a challenge that
 * points at the protected-resource metadata, so a client that has never
 * seen this server can discover where its trust comes from.
 */
@Configuration
public class McpDoor {

    public static final String MCP_ENDPOINT = "/mcp";
    public static final String MCP_AUDIENCE = "trustdesk-mcp";
    public static final String RESOURCE = "https://trustdesk.local/mcp";
    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    @Bean
    @Order(0)
    SecurityFilterChain mcpFilterChain(HttpSecurity http, DevelopmentJwtKeys keys) throws Exception {
        var decoder = mcpDecoder(keys);
        return http
                .securityMatcher(MCP_ENDPOINT, METADATA_PATH)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(METADATA_PATH).permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(sessions -> sessions
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(challengeWithMetadata())
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(McpDoor::describeThisResource))
                        .jwt(jwt -> jwt
                                .decoder(decoder)
                                .jwtAuthenticationConverter(AgentApiSecurityConfiguration.scopesAsAuthorities())))
                .build();
    }

    /** The well-known document, served by Spring Security: who we are and who may mint for us. Nothing secret. */
    static void describeThisResource(OAuth2ProtectedResourceMetadata.Builder metadata) {
        metadata.resource(RESOURCE)
                .resourceName("TrustDesk MCP")
                .authorizationServer(DevelopmentJwtKeys.ISSUER)
                .scopes(scopes -> scopes.addAll(List.of("agent:chat", "policies:it-hardware", "policies:finance-ops")));
    }

    /** Same key and issuer as the agent API; a different audience is the whole point. */
    public static JwtDecoder mcpDecoder(DevelopmentJwtKeys keys) throws JOSEException {
        var decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(DevelopmentJwtKeys.ISSUER),
                new JwtClaimValidator<Collection<String>>(
                        JwtClaimNames.AUD, audience -> audience != null && audience.contains(MCP_AUDIENCE))));
        return decoder;
    }

    /** 401 plus a pointer to the metadata document — the spec's discovery hook — never a redirect. */
    static AuthenticationEntryPoint challengeWithMetadata() {
        return (request, response, exception) -> {
            var challenge = new StringBuilder("Bearer realm=\"" + MCP_AUDIENCE + "\"");
            if (exception instanceof OAuth2AuthenticationException oauth2) {
                challenge.append(", error=\"").append(oauth2.getError().getErrorCode()).append('"');
            }
            var origin = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
            challenge.append(", resource_metadata=\"").append(origin).append(METADATA_PATH).append('"');
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge.toString());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        };
    }
}
