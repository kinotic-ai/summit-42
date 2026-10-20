package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAppBuilderService implements AppBuilderService {

    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/app-builder.md";
    private static final String TURN_RUNNING = "Claude is still working on your last message";
    private static final String TURN_FAILED = "Something went wrong. Please try again.";
    // the most recent messages of the conversation a turn sends Claude
    private static final int MAX_HISTORY_MESSAGES = 40;
    // a tool's progress event and the answer's completion can be emitted at the same moment; the sink serializes them
    // by retrying briefly
    private static final Sinks.EmitFailureHandler RETRY_CONCURRENT_EMIT = Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1));

    private final SecurityContext securityContext;
    private final BuilderSessionRepository sessionRepository;
    private final ChatClientFactory chatClientFactory;
    private final AppBuilderTools tools;
    private final SystemMessage systemMessage = new SystemMessage(loadSystemPrompt());

    @Override
    public Flux<BuildEvent> chat(String message) {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        Flux<BuildEvent> ret;
        if (message == null || message.isBlank()) {
            ret = Flux.just(BuildEvent.error("The message is empty"));
        } else {
            BuilderSession session = sessionRepository.findOrCreate(participant.getOrganizationId());
            // the turn takes the session when the stream is subscribed, so a stream never subscribed holds nothing
            ret = Flux.defer(() -> startTurn(participant, session, message));
        }
        return ret;
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
        Future<Void> ret;
        synchronized (session) {
            if (session.isTurnRunning()) {
                ret = Future.failedFuture(new IllegalStateException(TURN_RUNNING + "; reset the conversation once it is done"));
            } else {
                session.getConversation().getMessages().clear();
                ret = Future.succeededFuture();
            }
        }
        return ret;
    }

    private Flux<BuildEvent> startTurn(OrganizationParticipant participant, BuilderSession session, String message) {
        List<Message> prompt = null;
        synchronized (session) {
            if (!session.isTurnRunning()) {
                session.setTurnRunning(true);
                prompt = promptFor(session, message);
                session.getConversation().getMessages().add(new ChatMessage(ChatRole.USER, message));
            }
        }
        Flux<BuildEvent> ret;
        if (prompt == null) {
            ret = Flux.just(BuildEvent.error(TURN_RUNNING));
        } else {
            ret = runTurn(participant, session, prompt);
        }
        return ret;
    }

    private Flux<BuildEvent> runTurn(OrganizationParticipant participant, BuilderSession session, List<Message> prompt) {
        Sinks.Many<BuildEvent> progress = Sinks.many().unicast().onBackpressureBuffer();
        BuilderTurn turn = new BuilderTurn(participant, session, event -> progress.emitNext(event, RETRY_CONCURRENT_EMIT));
        // appended on the model's stream and read by a cancel on another thread
        StringBuffer reply = new StringBuffer();
        AtomicBoolean ended = new AtomicBoolean();

        Flux<BuildEvent> answer =
                Mono.fromCompletionStage(() -> chatClientFactory.chatClient(participant))
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
                        String text = e instanceof MissingLlmConfigurationException ? e.getMessage() : TURN_FAILED;
                        return Flux.just(BuildEvent.error(text));
                    })
                    // the progress stream ends with the answer, so the merged turn completes with it
                    .doFinally(signal -> progress.emitComplete(RETRY_CONCURRENT_EMIT));

        // runs before the stream's completion reaches the client, so a client that reads the conversation or sends its
        // next message on completion finds the reply recorded and the turn over
        Runnable endTurn = () -> {
            if (ended.compareAndSet(false, true)) {
                synchronized (session) {
                    if (!reply.isEmpty()) {
                        session.getConversation().getMessages().add(new ChatMessage(ChatRole.ASSISTANT, reply.toString()));
                    }
                }
                // a cancelled stream leaves the tool call it started running; the turn ends when that returns
                turn.release();
            }
        };
        return Flux.merge(answer, progress.asFlux())
                   .doOnTerminate(endTurn)
                   .doOnCancel(endTurn);
    }

    private BuilderSession currentSession() {
        OrganizationParticipant participant = securityContext.requireParticipant(OrganizationParticipant.class);
        return sessionRepository.findOrCreate(participant.getOrganizationId());
    }

    // the system prompt, the most recent messages of the conversation, and the new message; tool calls of earlier
    // turns are not kept, so Claude reads the repository again when it needs to
    private List<Message> promptFor(BuilderSession session, String message) {
        List<ChatMessage> messages = session.getConversation().getMessages();
        int from = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
        // a window that cuts an exchange in two drops the reply whose message it cut off
        if (from > 0 && messages.get(from).getRole() == ChatRole.ASSISTANT) {
            from++;
        }
        List<Message> ret = new ArrayList<>();
        ret.add(systemMessage);
        for (ChatMessage chatMessage : messages.subList(from, messages.size())) {
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
