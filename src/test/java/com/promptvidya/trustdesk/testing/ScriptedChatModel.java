package com.promptvidya.trustdesk.testing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * The model as a fixture. A script of turns — tool calls and answers —
 * played back in order, one per call; every prompt the client sent is
 * kept for assertions; an exhausted script fails the test loudly rather
 * than looping forever. It advertises tool-calling options on purpose:
 * without them the client drops the tool callbacks, the loop never
 * runs, and a test passes because nothing happened.
 */
public final class ScriptedChatModel implements ChatModel {

    private final Deque<ChatResponse> script = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();
    private int turns;

    private ScriptedChatModel() {
    }

    public static ScriptedChatModel script() {
        return new ScriptedChatModel();
    }

    /** The next turn asks for one tool with JSON arguments. */
    public ScriptedChatModel toolCall(String tool, String argumentsJson) {
        turns++;
        var call = new AssistantMessage.ToolCall("call-" + turns, "function", tool, argumentsJson);
        script.add(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build()))));
        return this;
    }

    /** The next turn answers in prose, which ends the loop. */
    public ScriptedChatModel answer(String text) {
        turns++;
        script.add(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(Objects.requireNonNull(prompt));
        var next = script.poll();
        if (next == null) {
            throw new IllegalStateException("script exhausted after " + prompts.size() + " calls");
        }
        return next;
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    public int invocations() {
        return prompts.size();
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    /** The tool result the client fed back on the most recent turn, if any. */
    public Optional<ToolResponseMessage> lastToolResponse() {
        if (prompts.isEmpty()) {
            return Optional.empty();
        }
        return prompts.getLast().getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .reduce((first, second) -> second);
    }
}
