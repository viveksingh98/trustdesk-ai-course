package com.promptvidya.trustdesk.observability;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;

/**
 * The budget as an advisor: outermost in the chain, so it runs before
 * the tool loop and before any model call. It reads the actor the
 * chat service placed in the advisor context, reserves an estimate,
 * and on refusal answers with a fixed message — no model, no spend —
 * while writing one evidence row and one counter tick. After a real
 * call it settles against the usage the model reported.
 */
public final class BudgetAdvisor implements CallAdvisor {

    public static final String ACTOR_KEY = "actor";
    static final String REFUSED_ACTION = "budget_refused";
    public static final String REFUSED_MESSAGE = "This request was not sent to the model: the budget or rate limit for this account is reached.";
    static final int CHARACTERS_PER_TOKEN = 4;

    private final TokenBudget budget;
    private final AgentMetrics metrics;
    private final AuditTrail audit;

    public BudgetAdvisor(TokenBudget budget, AgentMetrics metrics, AuditTrail audit) {
        this.budget = Objects.requireNonNull(budget);
        this.metrics = Objects.requireNonNull(metrics);
        this.audit = Objects.requireNonNull(audit);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!(request.context().get(ACTOR_KEY) instanceof ActorContext actor)) {
            throw new AccessDeniedException("budget requires an authenticated actor");
        }
        var estimate = estimateTokens(request.prompt().getInstructions());
        var refusal = budget.reserve(actor.subject(), estimate);
        if (refusal.isPresent()) {
            audit.record(actor.subject(), REFUSED_ACTION, "chat", refusal.get().name());
            metrics.refused(refusal.get().name());
            return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(REFUSED_MESSAGE)))))
                    .context(request.context())
                    .build();
        }
        var response = chain.nextCall(request);
        budget.settle(actor.subject(), estimate, actualTokens(response, estimate));
        return response;
    }

    /** The model's own usage when it reported one; otherwise the estimate plus the reply's size. */
    private long actualTokens(ChatClientResponse response, long estimate) {
        var chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            var reported = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
            if (reported > 0) {
                metrics.tokens(chatResponse.getMetadata().getModel(), usage.getPromptTokens(), usage.getCompletionTokens());
                return reported;
            }
        }
        var reply = chatResponse == null || chatResponse.getResult() == null ? "" : chatResponse.getResult().getOutput().getText();
        return estimate + estimateTokens(reply);
    }

    static long estimateTokens(List<Message> messages) {
        long characters = 0;
        for (var message : messages) {
            characters += message.getText() == null ? 0 : message.getText().length();
        }
        return Math.max(1, characters / CHARACTERS_PER_TOKEN);
    }

    static long estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / CHARACTERS_PER_TOKEN);
    }

    @Override
    public String getName() {
        return "trustdesk-budget";
    }

    /** Outermost: before the tool loop, before any model call. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1000;
    }
}
