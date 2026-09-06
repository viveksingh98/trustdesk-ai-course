package com.promptvidya.trustdesk.identity;

import java.util.Objects;
import java.util.Set;

public record ActorContext(String subject, Set<String> scopes) {

    public ActorContext {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes must not be null"));
    }
}
