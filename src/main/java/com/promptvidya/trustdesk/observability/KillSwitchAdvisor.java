package com.promptvidya.trustdesk.observability;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.observability.KillSwitch.Capability;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;

/**
 * The switch in front of the model: the very first advisor, so a
 * halted agent makes no model call, spends nothing, and answers every
 * request with one fixed sentence — while every refused request still
 * leaves a row, because an incident timeline needs to show what was
 * turned away as much as what ran.
 */
public final class KillSwitchAdvisor implements CallAdvisor {

    static final String REFUSED_ACTION = "chat_refused";
    public static final String PAUSED_MESSAGE = "The assistant is paused by an operator. Please try again later or contact IT.";

    private final KillSwitch killSwitch;
    private final AgentMetrics metrics;
    private final AuditTrail audit;

    public KillSwitchAdvisor(KillSwitch killSwitch, AgentMetrics metrics, AuditTrail audit) {
        this.killSwitch = Objects.requireNonNull(killSwitch);
        this.metrics = Objects.requireNonNull(metrics);
        this.audit = Objects.requireNonNull(audit);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (killSwitch.allows(Capability.CHAT)) {
            return chain.nextCall(request);
        }
        var subject = request.context().get(BudgetAdvisor.ACTOR_KEY) instanceof ActorContext actor ? actor.subject() : "anonymous";
        audit.record(subject, REFUSED_ACTION, "agent", killSwitch.mode().name());
        metrics.refused(killSwitch.mode().name());
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(PAUSED_MESSAGE)))))
                .context(request.context())
                .build();
    }

    @Override
    public String getName() {
        return "trustdesk-kill-switch";
    }

    /** Before the budget, before the loop, before everything. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 500;
    }
}
