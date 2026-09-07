package com.promptvidya.trustdesk.mcp;

import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * No token passthrough. When the MCP server must call a downstream
 * service, it never forwards the token it received: it mints one of its
 * own for the downstream audience — the person still the subject, this
 * server the actor, scope narrowed to what both the inbound token and
 * the call allow — so the downstream service sees who is really
 * calling and a leaked downstream token opens nothing here.
 */
@Service
public class DownstreamCredentials {

    public static final String SELF = "trustdesk-mcp";
    static final Duration LIFETIME = Duration.ofMinutes(2);

    private final JwtEncoder encoder;
    private final Clock clock;

    public DownstreamCredentials(JwtEncoder encoder, Clock clock) {
        this.encoder = Objects.requireNonNull(encoder);
        this.clock = Objects.requireNonNull(clock);
    }

    /** A fresh token for one downstream audience, derived from — never equal to — the inbound one. */
    public String forAudience(String downstreamAudience, Set<String> requestedScopes) {
        if (downstreamAudience == null || downstreamAudience.isBlank() || downstreamAudience.equals(SELF)) {
            throw new IllegalArgumentException("a downstream token needs a downstream audience");
        }
        var inbound = inboundToken();
        var granted = new TreeSet<>(requestedScopes);
        granted.retainAll(scopesOf(inbound));
        if (granted.isEmpty()) {
            throw new AccessDeniedException("downstream call would carry nothing the caller holds");
        }
        var now = clock.instant();
        var claims = JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(downstreamAudience))
                .subject(inbound.getSubject())
                .issuedAt(now)
                .expiresAt(now.plus(LIFETIME))
                .claim("scope", String.join(" ", granted))
                .claim("act", actorClaimFor(inbound))
                .build();
        var minted = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        if (minted.equals(inbound.getTokenValue())) {
            throw new IllegalStateException("refusing to pass the inbound token through");
        }
        return minted;
    }

    /** This server becomes the current actor; whoever acted before it nests inside, one level per hop. */
    private static Map<String, Object> actorClaimFor(Jwt inbound) {
        var previous = inbound.getClaimAsMap("act");
        return previous == null ? Map.of("sub", SELF) : Map.of("sub", SELF, "act", previous);
    }

    /** The token this request arrived with — read, never re-sent. */
    private static Jwt inboundToken() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("no authenticated inbound token to act for");
        }
        return token.getToken();
    }

    private static Set<String> scopesOf(Jwt jwt) {
        var scope = jwt.getClaimAsString("scope");
        return scope == null || scope.isBlank() ? Set.of() : Set.of(scope.trim().split("\\s+"));
    }
}
