package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.summit42.api.model.BuildEvent;
import org.kinotic.summit42.api.model.ChatMessage;
import org.kinotic.summit42.api.model.ChatRole;
import org.kinotic.summit42.api.model.Conversation;
import org.kinotic.summit42.api.services.AppBuilderService;
import org.kinotic.summit42.internal.api.repositories.BuilderSession;
import org.kinotic.summit42.internal.api.repositories.BuilderSessionRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DefaultAppBuilderService implements AppBuilderService {

    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/app-builder.md";
    // a tool and the model's stream can emit at the same moment; the sink serializes them by retrying briefly
    private static final Sinks.EmitFailureHandler RETRY_CONCURRENT_EMIT = Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1));

    private final SecurityContext securityContext;
    private final BuilderSessionRepository sessionRepository;
    private final ChatClientFactory chatClientFactory;
    private final AppBuilderTools tools;
    private final SystemMessage systemMessage;

    public DefaultAppBuilderService(SecurityContext securityContext,
                                    BuilderSessionRepository sessionRepository,
                                    ChatClientFactory chatClientFactory,
                                    AppBuilderTools tools) {
        this.securityContext = securityContext;
        this.sessionRepository = sessionRepository;
        this.chatClientFactory = chatClientFactory;
        this.tools = tools;
        this.systemMessage = new SystemMessage(loadSystemPrompt());
    }

    @Override
    public Flux<BuildEvent> chat(String message) {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        if (message == null || message.isBlank()) {
            return Flux.just(BuildEvent.error("The message is empty"));
        }
        BuilderSession session = sessionRepository.findOrCreate(participant.getOrganizationId());
        List<Message> prompt;
        synchronized (session) {
            if (session.isTurnRunning()) {
                return Flux.just(BuildEvent.error("Claude is still working on your last message"));
            }
            session.setTurnRunning(true);
            prompt = promptFor(session, message);
            session.getConversation().getMessages().add(new ChatMessage(ChatRole.USER, message));
        }

        Sinks.Many<BuildEvent> progress = Sinks.many().unicast().onBackpressureBuffer();
        BuilderTurn turn = new BuilderTurn(participant, session, event -> progress.emitNext(event, RETRY_CONCURRENT_EMIT));
        StringBuilder reply = new StringBuilder();

        Flux<BuildEvent> answer =
                Mono.fromCompletionStage(() -> chatClientFactory.chatClient().toCompletionStage())
                    .flatMapMany(client -> client.prompt()
                                                 .messages(prompt)
                                                 .tools(tools)
                                                 .toolContext(Map.of(AppBuilderTools.TURN, turn))
                                                 .stream()
                                                 .content())
                    .map(text -> {
                        reply.append(text);
                        return BuildEvent.text(text);
                    })
                    .onErrorResume(e -> {
                        log.error("Chat turn of organization {} failed", session.getOrganizationId(), e);
                        return Flux.just(BuildEvent.error("Something went wrong: " + e.getMessage()));
                    })
                    // the progress stream ends with the answer, so the merged turn completes with it
                    .doFinally(signal -> progress.emitComplete(RETRY_CONCURRENT_EMIT));

        return Flux.merge(answer, progress.asFlux())
                   .doFinally(signal -> {
                       synchronized (session) {
                           if (!reply.isEmpty()) {
                               session.getConversation().getMessages().add(new ChatMessage(ChatRole.ASSISTANT, reply.toString()));
                           }
                           session.setTurnRunning(false);
                       }
                   });
    }

    @Override
    public Future<Conversation> findConversation() {
        BuilderSession session = currentSession();
        Conversation ret;
        synchronized (session) {
            ret = new Conversation().setMessages(new ArrayList<>(session.getConversation().getMessages()))
                                    .setAppUrl(session.getConversation().getAppUrl());
        }
        return Future.succeededFuture(ret);
    }

    @Override
    public Future<Void> resetConversation() {
        BuilderSession session = currentSession();
        synchronized (session) {
            session.getConversation().getMessages().clear();
        }
        return Future.succeededFuture();
    }

    private BuilderSession currentSession() {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        return sessionRepository.findOrCreate(participant.getOrganizationId());
    }

    // the system prompt, the conversation so far, and the new message; tool calls of earlier turns are not kept,
    // so Claude reads the repository again when it needs to
    private List<Message> promptFor(BuilderSession session, String message) {
        List<Message> ret = new ArrayList<>();
        ret.add(systemMessage);
        for (ChatMessage chatMessage : session.getConversation().getMessages()) {
            ret.add(chatMessage.getRole() == ChatRole.USER
                            ? new UserMessage(chatMessage.getText())
                            : new AssistantMessage(chatMessage.getText()));
        }
        ret.add(new UserMessage(message));
        return ret;
    }

    private static String loadSystemPrompt() {
        try {
            return new ClassPathResource(SYSTEM_PROMPT_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + SYSTEM_PROMPT_RESOURCE, e);
        }
    }
}
