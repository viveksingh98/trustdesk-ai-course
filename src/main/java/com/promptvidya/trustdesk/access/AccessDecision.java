package com.promptvidya.trustdesk.access;

import java.util.UUID;

public record AccessDecision(UUID requestId, String status) {
}
