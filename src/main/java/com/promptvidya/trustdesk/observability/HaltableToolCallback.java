package com.promptvidya.trustdesk.observability;

import com.promptvidya.trustdesk.authz.ToolPolicy;
import com.promptvidya.trustdesk.observability.KillSwitch.Capability;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.access.AccessDeniedException;

/**
 * The switch at the tools: in read-only mode a tool that changes the
 * world does not run, whatever the model asked for. Which tools change
 * the world is the policy table's call — sensitive tools are writes,
 * unknown tools are treated as writes — so the switch and the decision
 * layer never disagree about what a tool is.
 */
public final class HaltableToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final KillSwitch killSwitch;
    private final ToolPolicy policy;

    public HaltableToolCallback(ToolCallback delegate, KillSwitch killSwitch, ToolPolicy policy) {
        this.delegate = Objects.requireNonNull(delegate);
        this.killSwitch = Objects.requireNonNull(killSwitch);
        this.policy = Objects.requireNonNull(policy);
    }

    public static ToolCallback[] haltable(KillSwitch killSwitch, ToolPolicy policy, ToolCallback... callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> new HaltableToolCallback(callback, killSwitch, policy))
                .toArray(ToolCallback[]::new);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        var name = delegate.getToolDefinition().name();
        var capability = policy.requirementFor(name)
                .map(requirement -> requirement.sensitive() ? Capability.TOOL_WRITE : Capability.TOOL_READ)
                .orElse(Capability.TOOL_WRITE);
        if (!killSwitch.allows(capability)) {
            throw new AccessDeniedException("agent is " + killSwitch.mode() + ": " + name);
        }
        return toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
    }
}
