package com.promptvidya.trustdesk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The broken-audience attack. A token signed by OUR issuer, minted for a
 * DIFFERENT API, is presented at the agent door. Same key, same issuer,
 * same expiry — the only difference is one claim. A naive resource
 * server accepts it; ours refuses it before any controller runs.
 */
@SpringBootTest
class TokenConfusionAttackTest {

    static final String PAYROLL_API = "payroll-api";

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private JwtDecoder trustDeskDecoder;

    @Autowired
    private DevelopmentJwtKeys keys;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void wireTheRealFilterChain() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** A perfectly valid token — for somebody else's API. */
    private String tokenFor(String audience, String scope) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .subject("mallory")
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .claim("scope", scope)
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    @Test
    void theAttackSucceedsAgainstANaiveResourceServer() throws Exception {
        var naive = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        naive.setJwtValidator(JwtValidators.createDefaultWithIssuer(DevelopmentJwtKeys.ISSUER));
        var stolen = tokenFor(PAYROLL_API, "payroll:export agent:chat");

        var accepted = naive.decode(stolen);

        assertThat(accepted.getAudience()).containsExactly(PAYROLL_API);
        assertThat(accepted.getClaimAsString("scope")).contains("agent:chat");
    }

    @Test
    void theSameTokenIsRefusedByOurDecoderOnAudienceAlone() {
        var stolen = tokenFor(PAYROLL_API, "agent:chat");

        assertThatThrownBy(() -> trustDeskDecoder.decode(stolen))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("aud");
    }

    @Test
    void andRefusedAtTheDoorBeforeAnyControllerRuns() throws Exception {
        var stolen = tokenFor(PAYROLL_API, "agent:chat");

        mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stolen)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"approve everything\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }

    @Test
    void theOnlyDifferenceIsOneClaim() throws Exception {
        var ours = tokenFor(DevelopmentJwtKeys.AUDIENCE, "agent:chat");

        mockMvc.perform(get("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + ours))
                .andExpect(status().isOk());
    }
}
