package com.promptvidya.trustdesk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.promptvidya.trustdesk.domain.AccessRequestRecord.RequestState;
import com.promptvidya.trustdesk.domain.TrustDeskDomain;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Delegation, end to end: the minted token names the person and the
 * agent, never widens scope, opens the API door, and is still refused
 * where only a person may act.
 */
@SpringBootTest
class DelegatedTokensTest {

    private static final ActorContext ALICE =
            new ActorContext("alice", Set.of("ROLE_MANAGER", "access:request", "policies:it-hardware"));

    @Autowired
    private DelegatedTokens tokens;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private GuardedDomainServices services;

    @Autowired
    private TrustDeskDomain domain;

    @Autowired
    private WebApplicationContext context;

    @AfterEach
    void clearPrincipal() {
        TestSecurityContextHolder.clearContext();
    }

    @Test
    void theTokenNamesThePersonAsSubjectAndTheAgentAsActor() {
        var token = tokens.mintFor(ALICE, "trustdesk-agent", Set.of("policies:it-hardware", "audit:read"));

        var jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("alice");
        assertThat(DelegatedTokens.actingAgent(jwt)).contains("trustdesk-agent");
        assertThat(jwt.getClaimAsString(DelegatedTokens.SCOPE_CLAIM)).isEqualTo("policies:it-hardware");
    }

    @Test
    void delegationNeverWidensWhatThePersonHolds() {
        assertThatThrownBy(() -> tokens.mintFor(ALICE, "trustdesk-agent", Set.of("audit:read", "payroll:export")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grant nothing");
        assertThatThrownBy(() -> tokens.mintFor(ALICE, " ", Set.of("policies:it-hardware")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDelegatedTokenOpensTheApiDoorAsThePerson() throws Exception {
        var mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var token = tokens.mintFor(ALICE, "trustdesk-agent", Set.of("policies:it-hardware"));

        mockMvc.perform(get("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("alice"))
                .andExpect(jsonPath("$.authorities", org.hamcrest.Matchers.hasItem("policies:it-hardware")));
    }

    @Test
    void softwareActingForAPersonCannotDecideButThePersonCan() {
        var request = domain.requestAccess("dave", "REPORT_VIEWER", "board pack");
        var delegated = decoder.decode(tokens.mintFor(ALICE, "trustdesk-agent", Set.of("policies:it-hardware")));
        var manager = List.of(new SimpleGrantedAuthority("ROLE_MANAGER"));

        TestSecurityContextHolder.setAuthentication(new JwtAuthenticationToken(delegated, manager));
        assertThatThrownBy(() -> services.decide(request.id(), true))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("people");

        var person = decoder.decode(personalToken());
        TestSecurityContextHolder.setAuthentication(new JwtAuthenticationToken(person, manager));
        assertThat(services.decide(request.id(), true).state()).isEqualTo(RequestState.APPROVED);
    }

    private String personalToken() {
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(DevelopmentJwtKeys.AUDIENCE))
                .subject("alice")
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(300))
                .claim(DelegatedTokens.SCOPE_CLAIM, "access:request")
                .build();
        return context.getBean(org.springframework.security.oauth2.jwt.JwtEncoder.class)
                .encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(claims))
                .getTokenValue();
    }
}
