package com.promptvidya.trustdesk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.promptvidya.trustdesk.agent.AgentChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The front door, exercised through the real filter chain: anonymous
 * callers are sent to login, roles gate URLs before any controller runs,
 * and a form login produces a session whose authorities are exactly the
 * scopes the tool chain consumes.
 */
@SpringBootTest
class TrustDeskSecurityConfigurationTest {

    private static final String QUESTION = "{\"message\":\"How often are laptops replaced?\"}";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private AgentChatService agent;

    private MockMvc mockMvc;

    @BeforeEach
    void wireTheRealFilterChain() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousCallersAreSentToLoginNotToTheModel() throws Exception {
        mockMvc.perform(post("/chat").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(username = "alice", roles = RoleGrants.EMPLOYEE)
    void employeesReachTheChatEndpoint() throws Exception {
        when(agent.chat(any(), anyList(), any())).thenReturn("Every three years.");

        mockMvc.perform(post("/chat").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Every three years.")));
    }

    @Test
    @WithMockUser(username = "auditor", roles = RoleGrants.AUDITOR)
    void urlRulesAreDecidedBeforeRouting() throws Exception {
        mockMvc.perform(post("/chat").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(QUESTION))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/audit/events")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice", roles = RoleGrants.EMPLOYEE)
    void employeesNeverReachTheAuditTrail() throws Exception {
        mockMvc.perform(get("/audit/events")).andExpect(status().isForbidden());
    }

    @Test
    void formLoginProducesASessionCarryingTheRoleAndItsScopes() throws Exception {
        mockMvc.perform(formLogin().user("alice").password("dev-only-password"))
                .andExpect(authenticated().withUsername("alice").withAuthentication(authentication ->
                        assertThat(authentication.getAuthorities())
                                .extracting(GrantedAuthority::getAuthority)
                                .contains("ROLE_EMPLOYEE", "access:request", "policies:it-hardware")
                                .doesNotContain("audit:read")));
    }
}
