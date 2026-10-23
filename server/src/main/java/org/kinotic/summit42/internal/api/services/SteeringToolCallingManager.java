package org.kinotic.summit42.internal.api.services;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the tool calls of a chat turn's step, then adds the messages the user sent to steer the turn after their
 * results, so Claude reads them before its next step.
 */
@Component
public class SteeringToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate = ToolCallingManager.builder().build();

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ToolExecutionResult ret = delegate.executeToolCalls(prompt, chatResponse);
        BuilderTurn turn = (BuilderTurn) ((ToolCallingChatOptions) prompt.getOptions()).getToolContext().get(AppBuilderTools.TURN);
        List<String> steering = turn.takeSteering();
        if (!steering.isEmpty()) {
            // a user message after the tool results joins their turn, as Anthropic's API combines consecutive user turns
            List<Message> history = new ArrayList<>(ret.conversationHistory());
            steering.forEach(message -> history.add(new UserMessage(message)));
            ret = ToolExecutionResult.builder()
                                     .conversationHistory(history)
                                     .returnDirect(ret.returnDirect())
                                     .build();
        }
        return ret;
    }
}
