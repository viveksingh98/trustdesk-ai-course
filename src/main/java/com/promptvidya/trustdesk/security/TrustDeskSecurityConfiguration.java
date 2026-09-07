package com.promptvidya.trustdesk.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TrustDesk's front door for people: form login, a server-side session
 * that is renewed on login and limited to one per user, and URL rules
 * decided before any controller runs.
 *
 * <p>Users are in-memory for the course. The roles-to-scopes mapping in
 * {@link RoleGrants} is what the rest of the application consumes, so
 * swapping the user store later changes nothing downstream.
 */
@Configuration
@EnableWebSecurity
public class TrustDeskSecurityConfiguration {

    private final String developmentPassword;

    public TrustDeskSecurityConfiguration(
            @Value("${trustdesk.security.dev-password:dev-only-password}") String developmentPassword) {
        this.developmentPassword = developmentPassword;
    }

    @Bean
    SecurityFilterChain trustDeskFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/audit/**").hasRole(RoleGrants.AUDITOR)
                        .requestMatchers("/chat").hasAnyRole(RoleGrants.EMPLOYEE, RoleGrants.MANAGER)
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults())
                .sessionManagement(sessions -> sessions
                        .sessionFixation(fixation -> fixation.newSession())
                        .maximumSessions(1))
                .build();
    }

    @Bean
    UserDetailsService trustDeskUsers(PasswordEncoder encoder) {
        var encoded = encoder.encode(developmentPassword);
        return new InMemoryUserDetailsManager(
                User.withUsername("alice")
                        .password(encoded)
                        .authorities(RoleGrants.authoritiesFor(RoleGrants.EMPLOYEE))
                        .build(),
                User.withUsername("hardware-lead")
                        .password(encoded)
                        .authorities(RoleGrants.authoritiesFor(RoleGrants.MANAGER))
                        .build(),
                User.withUsername("auditor")
                        .password(encoded)
                        .authorities(RoleGrants.authoritiesFor(RoleGrants.AUDITOR))
                        .build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
