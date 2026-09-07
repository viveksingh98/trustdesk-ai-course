package com.promptvidya.trustdesk.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who asked for what, as evidence. A person acting directly has an empty
 * chain; software acting for them appears as actors, in the order the
 * request travelled: the person, then the agent, then the server the
 * agent called. Read from the token-exchange actor claim — {@code act},
 * nested one level per hop — never from a tool argument.
 */
public record DelegationChain(String subject, List<String> actors) {

    static final String ACTOR_CLAIM = "act";
    static final int MAXIMUM_REFERENCE_CHARACTERS = 200;

    public DelegationChain {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("a delegation chain starts with a person");
        }
        actors = List.copyOf(actors);
        if (actors.stream().anyMatch(actor -> actor == null || actor.isBlank())) {
            throw new IllegalArgumentException("every actor in a chain is named");
        }
    }

    public static DelegationChain person(String subject) {
        return new DelegationChain(subject, List.of());
    }

    /** Outermost act is the current actor; nested acts are the hops before it — so the list is reversed into travel order. */
    public static DelegationChain fromJwt(Jwt jwt) {
        var hops = new ArrayList<String>();
        var actor = jwt.getClaimAsMap(ACTOR_CLAIM);
        while (actor != null) {
            if (actor.get("sub") instanceof String name && !name.isBlank()) {
                hops.add(name);
            }
            actor = actor.get(ACTOR_CLAIM) instanceof Map<?, ?> nested ? castClaims(nested) : null;
        }
        return new DelegationChain(jwt.getSubject(), hops.reversed());
    }

    public static DelegationChain fromAuthentication(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            return fromJwt(token.getToken());
        }
        return person(authentication.getName());
    }

    public boolean delegated() {
        return !actors.isEmpty();
    }

    /** "alice via trustdesk-agent via trustdesk-mcp" — a reference, short enough for the trail. */
    public String describe() {
        return actors.isEmpty() ? subject : subject + " via " + String.join(" via ", actors);
    }

    public String reference(String tool) {
        var reference = tool + "@" + describe();
        return reference.length() <= MAXIMUM_REFERENCE_CHARACTERS
                ? reference
                : reference.substring(0, MAXIMUM_REFERENCE_CHARACTERS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castClaims(Map<?, ?> claims) {
        return (Map<String, Object>) claims;
    }
}
