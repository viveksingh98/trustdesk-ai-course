package com.promptvidya.trustdesk.authz;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which tool needs which grant. A tool that is not in the policy does
 * not run — deny by default is a property of the table, not of the
 * callers. Scopes ending in {@code :*} match any grant with that prefix
 * (a shelf grant such as {@code policies:it-hardware} satisfies
 * {@code policies:*}); sensitive tools additionally require a stated
 * intent.
 */
public record ToolPolicy(Map<String, Requirement> requirements) {

    public record Requirement(String scope, boolean sensitive) {
        public Requirement {
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("a requirement names a scope");
            }
        }

        boolean satisfiedBy(Set<String> granted) {
            if (scope.endsWith(":*")) {
                var prefix = scope.substring(0, scope.length() - 1);
                return granted.stream().anyMatch(grant -> grant.startsWith(prefix));
            }
            return granted.contains(scope);
        }
    }

    public ToolPolicy {
        requirements = Map.copyOf(Objects.requireNonNull(requirements));
    }

    /** TrustDesk's table: reads need a read grant, the one write needs its own grant and an intent. */
    public static ToolPolicy trustDesk() {
        return new ToolPolicy(Map.of(
                "myOpenTickets", new Requirement("tickets:read", false),
                "ticketById", new Requirement("tickets:read", false),
                "ticket_by_id", new Requirement("tickets:read", false),
                "policyArticle", new Requirement("policies:*", false),
                "policy_article", new Requirement("policies:*", false),
                "requestAccess", new Requirement("access:request", true)));
    }

    public Optional<Requirement> requirementFor(String tool) {
        return Optional.ofNullable(requirements.get(tool));
    }
}
