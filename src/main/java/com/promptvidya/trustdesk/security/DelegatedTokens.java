package com.promptvidya.trustdesk.security;

import com.promptvidya.trustdesk.identity.ActorContext;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Acting for, not as. A delegated token names the person as its subject
 * and the agent as its actor, and carries only the scopes both the
 * person holds and the task requested — so nothing downstream ever
 * mistakes software for a person, and a compromised agent can spend
 * less than the person could.
 *
 * <p>The actor claim follows OAuth token exchange: {@code act} with the
 * agent's own subject inside it. Production obtains such tokens from
 * the identity provider; the shape is the same.
 */
@Service
public class DelegatedTokens {

    public static final String ACTOR_CLAIM = "act";
    public static final String SCOPE_CLAIM = "scope";
    static final Duration LIFETIME = Duration.ofMinutes(5);

    private final JwtEncoder encoder;
    private final Clock clock;

    public DelegatedTokens(JwtEncoder encoder, Clock clock) {
        this.encoder = Objects.requireNonNull(encoder);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Scope is the intersection: delegation can narrow a person's authority, never widen it. */
    public String mintFor(ActorContext person, String agentId, Set<String> requestedScopes) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("a delegated token must name the acting agent");
        }
        var granted = new TreeSet<>(requestedScopes);
        granted.retainAll(person.scopes());
        if (granted.isEmpty()) {
            throw new IllegalArgumentException("delegation would grant nothing the person holds");
        }
        var now = clock.instant();
        var claims = JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(DevelopmentJwtKeys.AUDIENCE))
                .subject(person.subject())
                .issuedAt(now)
                .expiresAt(now.plus(LIFETIME))
                .claim(SCOPE_CLAIM, String.join(" ", granted))
                .claim(ACTOR_CLAIM, Map.of("sub", agentId))
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /** The agent named in the actor claim, or empty when a person holds the token directly. */
    public static Optional<String> actingAgent(Jwt jwt) {
        var actor = jwt.getClaimAsMap(ACTOR_CLAIM);
        if (actor == null || !(actor.get("sub") instanceof String agent) || agent.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(agent);
    }

    /** The same question for any principal: is software acting for this person? */
    public static boolean isDelegated(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token
                && actingAgent(token.getToken()).isPresent();
    }
}
