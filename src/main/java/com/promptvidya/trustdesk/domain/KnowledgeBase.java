package com.promptvidya.trustdesk.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The support knowledge base: content employees ask about, with an owner
 * on every article. Provenance exists here, before any retrieval system
 * ever indexes a word — so a retrieved passage can always say who is
 * accountable for it.
 */
public final class KnowledgeBase {

    /** One article. The slug is validated like a tool input; the owner is mandatory. */
    public record Article(String slug, String title, String owner, String body) {

        private static final Pattern SAFE_SLUG = Pattern.compile("[a-z0-9-]{1,64}");

        public Article {
            if (slug == null || !SAFE_SLUG.matcher(slug).matches()) {
                throw new IllegalArgumentException("article slug must be a short lowercase slug");
            }
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("every article needs an owner");
            }
            Objects.requireNonNull(title);
            Objects.requireNonNull(body);
        }
    }

    private final Map<String, Article> articles = new ConcurrentHashMap<>();

    public static KnowledgeBase seeded() {
        var base = new KnowledgeBase();
        base.publish(new Article("laptop-refresh", "Laptop refresh policy", "it-hardware",
                "Laptops are replaced every three years or on hardware failure."));
        base.publish(new Article("vpn-troubleshooting", "VPN troubleshooting", "network-team",
                "Reconnect, then rotate the client certificate if drops persist."));
        base.publish(new Article("payroll-export-access", "Payroll export access", "finance-ops",
                "Payroll exports require the REPORT_VIEWER entitlement and a manager approval."));
        return base;
    }

    public Optional<Article> article(String slug) {
        return Optional.ofNullable(articles.get(slug));
    }

    public List<Article> articlesOwnedBy(String owner) {
        return articles.values().stream()
                .filter(article -> article.owner().equals(owner))
                .sorted((a, b) -> a.slug().compareTo(b.slug()))
                .toList();
    }

    public List<Article> all() {
        return articles.values().stream()
                .sorted((a, b) -> a.slug().compareTo(b.slug()))
                .toList();
    }

    public Article publish(Article article) {
        var existing = articles.putIfAbsent(article.slug(), article);
        if (existing != null) {
            throw new IllegalArgumentException("an article with this slug already exists");
        }
        return article;
    }

    public int size() {
        return articles.size();
    }
}
