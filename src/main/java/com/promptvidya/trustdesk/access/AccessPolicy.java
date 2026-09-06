package com.promptvidya.trustdesk.access;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record AccessPolicy(Set<String> privilegedEntitlements) {

    public AccessPolicy {
        privilegedEntitlements = Objects.requireNonNull(
                        privilegedEntitlements, "privilegedEntitlements must not be null")
                .stream()
                .map(AccessPolicy::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean requiresHumanApproval(String entitlement) {
        var normalizedEntitlement = normalize(entitlement);
        return normalizedEntitlement.equals("ADMIN") || privilegedEntitlements.contains(normalizedEntitlement);
    }

    private static String normalize(String entitlement) {
        return Objects.requireNonNull(entitlement, "entitlement must not be null")
                .trim()
                .toUpperCase(Locale.ROOT);
    }
}
