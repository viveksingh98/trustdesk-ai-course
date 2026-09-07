package com.promptvidya.trustdesk.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * The gate, offline: a production-shaped environment passes; each
 * development default is named; the message never echoes a value; and
 * under the prod profile a bad environment stops the context from
 * starting at all, while a good one starts.
 */
class ProductionReadinessTest {

    private static MockEnvironment productionShaped() {
        return new MockEnvironment()
                .withProperty("spring.ai.openai.api-key", "sk-live-example-not-a-real-key")
                .withProperty("trustdesk.security.dev-password", "")
                .withProperty("trustdesk.security.dev-keys.enabled", "false")
                .withProperty("spring.ai.chat.observations.log-prompt", "false")
                .withProperty("spring.ai.chat.observations.log-completion", "false")
                .withProperty("spring.ai.tools.observations.include-content", "false")
                .withProperty("management.endpoints.web.exposure.include", "health,info,prometheus")
                .withProperty("server.forward-headers-strategy", "framework");
    }

    @Test
    void aProductionShapedEnvironmentIsReady() {
        assertThat(ProductionReadiness.failures(productionShaped())).isEmpty();
    }

    @Test
    void theDevelopmentDefaultsAreEachNamed() {
        var dev = new MockEnvironment();

        assertThat(ProductionReadiness.failures(dev)).containsExactly(
                "model-key-is-real",
                "no-development-password",
                "development-jwt-keys-off",
                "forwarded-headers-handled");
    }

    @Test
    void flippedContentSwitchesAndWideActuatorExposureAreCaught() {
        var risky = productionShaped()
                .withProperty("spring.ai.chat.observations.log-prompt", "true")
                .withProperty("spring.ai.tools.observations.include-content", "true")
                .withProperty("management.endpoints.web.exposure.include", "health, ENV, prometheus");

        assertThat(ProductionReadiness.failures(risky)).containsExactly(
                "prompt-logging-off", "tool-content-logging-off", "actuator-exposure-bounded");
    }

    @Test
    void underTheProdProfileABadEnvironmentStopsTheContextAndAGoodOneStarts() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(ProductionReadiness.class)
                .withPropertyValues("spring.profiles.active=prod");

        runner.withPropertyValues("spring.ai.openai.api-key=demo-placeholder").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("production readiness failed")
                    .hasStackTraceContaining("model-key-is-real");
            assertThat(String.valueOf(context.getStartupFailure().getMessage())).doesNotContain("demo-placeholder");
        });

        runner.withPropertyValues(
                        "spring.ai.openai.api-key=sk-live-example-not-a-real-key",
                        "trustdesk.security.dev-password=",
                        "trustdesk.security.dev-keys.enabled=false",
                        "management.endpoints.web.exposure.include=health",
                        "server.forward-headers-strategy=framework")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProductionReadiness.class));
    }
}
