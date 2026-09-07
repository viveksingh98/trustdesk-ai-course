package com.promptvidya.trustdesk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.promptvidya.trustdesk.domain.KnowledgeBase.Article;
import org.junit.jupiter.api.Test;

class KnowledgeBaseTest {

    private final KnowledgeBase base = KnowledgeBase.seeded();

    @Test
    void everyArticleHasAnOwner() {
        assertThat(base.all()).allSatisfy(article -> assertThat(article.owner()).isNotBlank());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Article("orphan", "No owner", " ", "body"))
                .withMessageContaining("owner");
    }

    @Test
    void slugsAreValidatedLikeToolInputs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Article("../etc/passwd", "Bad", "it-hardware", "body"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Article("http://169.254.169.254/", "Bad", "it-hardware", "body"));
    }

    @Test
    void seededArticlesResolveBySlugWithProvenance() {
        assertThat(base.article("laptop-refresh")).get()
                .extracting(Article::owner).isEqualTo("it-hardware");
        assertThat(base.articlesOwnedBy("finance-ops")).extracting(Article::slug)
                .containsExactly("payroll-export-access");
        assertThat(base.article("missing")).isEmpty();
    }

    @Test
    void publishingRefusesDuplicateSlugs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> base.publish(new Article("laptop-refresh", "Dup", "x-team", "b")))
                .withMessageContaining("already exists");
    }
}
