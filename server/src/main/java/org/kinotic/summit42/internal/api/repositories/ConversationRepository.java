package org.kinotic.summit42.internal.api.repositories;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.kinotic.domain.api.model.ApplicationKey;
import org.kinotic.sql.domain.Migration;
import org.kinotic.sql.domain.ResourceMigration;
import org.kinotic.sql.executor.MigrationExecutor;
import org.kinotic.sql.parsers.MigrationParser;
import org.kinotic.summit42.api.model.Conversation;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Stores each app's {@link Conversation} in Elasticsearch, so it outlives the server.
 */
@Component
@RequiredArgsConstructor
public class ConversationRepository {

    private static final String INDEX = "summit42_conversation";
    private static final String MIGRATIONS = "classpath:summit42/migrations/*.sql";
    // the project MigrationExecutor records Summit 42's own migrations under, apart from Kinotic's and its apps'
    private static final String MIGRATION_PROJECT = "summit_42";

    private final ElasticsearchAsyncClient client;
    private final Vertx vertx;
    private final MigrationExecutor migrationExecutor;
    private final MigrationParser migrationParser;

    // blocks startup until the index exists, so no request reaches the repository before it does
    @PostConstruct
    public void migrate() throws IOException, ExecutionException, InterruptedException {
        List<Migration> migrations = Arrays.stream(new PathMatchingResourcePatternResolver().getResources(MIGRATIONS))
                                           .<Migration>map(resource -> new ResourceMigration(resource, migrationParser))
                                           .toList();
        migrationExecutor.ensureMigrationIndexExists()
                         .thenCompose(created -> migrationExecutor.executeProjectMigrations(migrations, MIGRATION_PROJECT))
                         .get();
    }

    /**
     * @return the app's conversation, or null when none has been saved
     */
    public Future<Conversation> findById(ApplicationKey applicationKey) {
        return Future.fromCompletionStage(client.get(get -> get.index(INDEX).id(idOf(applicationKey)), Conversation.class),
                                          vertx.getOrCreateContext())
                     .map(response -> response.found() ? response.source() : null);
    }

    /**
     * Replaces the app's saved conversation with {@code conversation}.
     */
    public Future<Void> save(ApplicationKey applicationKey, Conversation conversation) {
        return Future.fromCompletionStage(client.index(index -> index.index(INDEX)
                                                                     .id(idOf(applicationKey))
                                                                     .document(conversation)),
                                          vertx.getOrCreateContext())
                     .mapEmpty();
    }

    /**
     * Deletes the app's saved conversation, if it has one.
     */
    public Future<Void> deleteById(ApplicationKey applicationKey) {
        return Future.fromCompletionStage(client.delete(delete -> delete.index(INDEX).id(idOf(applicationKey))),
                                          vertx.getOrCreateContext())
                     .mapEmpty();
    }

    private static String idOf(ApplicationKey applicationKey) {
        return applicationKey.organizationId() + ":" + applicationKey.applicationId();
    }
}
