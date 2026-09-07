package com.promptvidya.trustdesk.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The production build refuses to start with development defaults.
 * Under the {@code prod} profile this gate runs once every singleton
 * exists and checks a short list — a real model key, no development
 * password, content logging off, development JWT keys disabled,
 * actuator exposure bounded, forwarded headers handled — and names
 * every failed check by name, never by value, so the failure message
 * is safe in a startup log.
 */
@Component
@Profile("prod")
public final class ProductionReadiness implements SmartInitializingSingleton {

    /** One property to inspect and the rule its value must satisfy. */
    public record Check(String name, String property, String fallback, Predicate<String> acceptable) {}

    static final String PLACEHOLDER_KEY = "demo-placeholder";
    static final String DEV_PASSWORD = "dev-only-password";
    static final Set<String> FORBIDDEN_EXPOSURES = Set.of("*", "env", "heapdump", "threaddump", "configprops", "beans");

    static final List<Check> CHECKS = List.of(
            new Check("model-key-is-real", "spring.ai.openai.api-key", "",
                    value -> !value.isBlank() && !value.equals(PLACEHOLDER_KEY)),
            new Check("no-development-password", "trustdesk.security.dev-password", DEV_PASSWORD,
                    value -> !value.equals(DEV_PASSWORD)),
            new Check("development-jwt-keys-off", "trustdesk.security.dev-keys.enabled", "true",
                    value -> value.equals("false")),
            new Check("prompt-logging-off", "spring.ai.chat.observations.log-prompt", "false",
                    value -> value.equals("false")),
            new Check("completion-logging-off", "spring.ai.chat.observations.log-completion", "false",
                    value -> value.equals("false")),
            new Check("tool-content-logging-off", "spring.ai.tools.observations.include-content", "false",
                    value -> value.equals("false")),
            new Check("actuator-exposure-bounded", "management.endpoints.web.exposure.include", "health",
                    value -> Set.of(value.toLowerCase().split("\\s*,\\s*")).stream().noneMatch(FORBIDDEN_EXPOSURES::contains)),
            new Check("forwarded-headers-handled", "server.forward-headers-strategy", "none",
                    value -> value.equals("framework") || value.equals("native")));

    private final Environment environment;

    public ProductionReadiness(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    /** The names of every check the environment fails; empty means ready. */
    public static List<String> failures(Environment environment) {
        var failed = new ArrayList<String>();
        for (var check : CHECKS) {
            var value = environment.getProperty(check.property(), check.fallback());
            if (!check.acceptable().test(value)) {
                failed.add(check.name());
            }
        }
        return List.copyOf(failed);
    }

    @Override
    public void afterSingletonsInstantiated() {
        var failed = failures(environment);
        if (!failed.isEmpty()) {
            throw new IllegalStateException("production readiness failed: " + String.join(", ", failed));
        }
    }
}
