package com.promptvidya.trustdesk.agent;

import java.net.URI;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;

/**
 * A fetching tool built so the model can never choose where it reaches:
 * the destination host is fixed, the model supplies only a document
 * name, and whatever comes back is capped and fenced as untrusted data
 * before it re-enters the window.
 */
public final class PolicyDocumentTool {

    static final URI POLICY_BASE = URI.create("https://policies.trustdesk.internal/docs/");
    static final int MAXIMUM_RESULT_CHARACTERS = 4000;
    private static final Pattern SAFE_DOCUMENT_NAME = Pattern.compile("[a-z0-9-]{1,64}");

    private final Function<URI, String> fetcher;

    public PolicyDocumentTool(Function<URI, String> fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher);
    }

    @Tool(description = "Read one TrustDesk policy document by its short name")
    public String readPolicyDocument(String documentName) {
        if (documentName == null || !SAFE_DOCUMENT_NAME.matcher(documentName).matches()) {
            throw new IllegalArgumentException("document name must be a short lowercase slug");
        }
        var target = POLICY_BASE.resolve(documentName);
        if (!POLICY_BASE.getHost().equals(target.getHost())) {
            throw new IllegalArgumentException("document name escaped the policy host");
        }
        var body = Objects.requireNonNullElse(fetcher.apply(target), "");
        var bounded = body.length() <= MAXIMUM_RESULT_CHARACTERS
                ? body
                : body.substring(0, MAXIMUM_RESULT_CHARACTERS);
        return """
                <tool_result source="policy-docs" trust="untrusted-data">
                %s
                </tool_result>
                """.formatted(bounded);
    }
}
