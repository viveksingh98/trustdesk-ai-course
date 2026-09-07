package com.promptvidya.trustdesk.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The bearer door, one check at a time: no token, wrong audience, wrong
 * issuer, expired, and finally a token minted for this API whose scopes
 * arrive as authorities.
 */
@SpringBootTest
class AgentApiSecurityConfigurationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JwtEncoder encoder;

    private MockMvc mockMvc;

    @BeforeEach
    void wireTheRealFilterChain() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String token(String issuer, String audience, Instant expiresAt, String scope) {
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("agent-for-alice")
                .audience(List.of(audience))
                .issuedAt(expiresAt.minus(Duration.ofMinutes(5)))
                .expiresAt(expiresAt)
                .claim("scope", scope)
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private String validToken(String scope) {
        return token(DevelopmentJwtKeys.ISSUER, DevelopmentJwtKeys.AUDIENCE, Instant.now().plus(Duration.ofMinutes(5)), scope);
    }

    @Test
    void noTokenIsAChallengeNotARedirect() throws Exception {
        mockMvc.perform(get("/api/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));
    }

    @Test
    void aTokenMintedForAnotherApiIsRefused() throws Exception {
        var forSomeoneElse = token(DevelopmentJwtKeys.ISSUER, "payroll-api",
                Instant.now().plus(Duration.ofMinutes(5)), "agent:chat");

        mockMvc.perform(get("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + forSomeoneElse))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }

    @Test
    void wrongIssuerAndExpiredTokensAreRefused() throws Exception {
        var wrongIssuer = token("https://evil.example/issuer", DevelopmentJwtKeys.AUDIENCE,
                Instant.now().plus(Duration.ofMinutes(5)), "agent:chat");
        var expired = token(DevelopmentJwtKeys.ISSUER, DevelopmentJwtKeys.AUDIENCE,
                Instant.now().minus(Duration.ofMinutes(5)), "agent:chat");

        mockMvc.perform(get("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongIssuer))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scopesInTheTokenBecomeAuthoritiesVerbatim() throws Exception {
        var token = validToken("agent:chat policies:it-hardware");

        mockMvc.perform(get("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("agent-for-alice"))
                .andExpect(jsonPath("$.authorities", hasItems("agent:chat", "policies:it-hardware")))
                .andExpect(jsonPath("$.authorities", hasItem("FACTOR_BEARER")))
                .andExpect(jsonPath("$.authorities", not(hasItem("SCOPE_agent:chat"))));
    }

    @Test
    void chatNeedsTheChatScopeEvenWithAValidToken() throws Exception {
        var readOnly = validToken("policies:it-hardware");

        mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isForbidden());
    }
}
