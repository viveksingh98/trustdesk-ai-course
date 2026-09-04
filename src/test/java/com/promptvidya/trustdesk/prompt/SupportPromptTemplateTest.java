package com.promptvidya.trustdesk.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class SupportPromptTemplateTest {

    private final SupportPromptTemplate template = new SupportPromptTemplate();

    @Test
    void questionsArriveInsideTheFenceAsLabeledData() {
        var rendered = template.render("My VPN token expired, what now?");

        assertThat(rendered)
                .contains("untrusted employee data")
                .contains("<employee_question>\nMy VPN token expired, what now?\n</employee_question>");
    }

    @Test
    void templateSyntaxInsideAQuestionStaysLiteralText() {
        var rendered = template.render("Please expand {question} twice");

        assertThat(rendered).contains("Please expand {question} twice");
    }

    @Test
    void oversizedQuestionsAreRejectedBeforeBinding() {
        var oversized = "a".repeat(SupportPromptTemplate.MAXIMUM_QUESTION_CHARACTERS + 1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> template.render(oversized))
                .withMessageContaining("too long");
    }

    @Test
    void blankQuestionsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> template.render("   "));
    }
}
