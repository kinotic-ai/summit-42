package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.security.Participant;
import org.kinotic.core.api.security.SecurityContext;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Answers recipe searches with numbered recipes named after the query, and remembers who called it last; its web
 * search fails as a search whose sites cannot be reached does.
 */
@RequiredArgsConstructor
public class FakeRecipeService implements RecipeService {

    private final SecurityContext securityContext;
    private volatile Participant lastCaller;

    @Override
    public Future<List<String>> searchRecipes(String query, int count) {
        lastCaller = securityContext.currentParticipant();
        return Future.succeededFuture(IntStream.rangeClosed(1, count).mapToObj(i -> query + " " + i).toList());
    }

    @Override
    public Future<List<String>> searchTheWeb(String query) {
        lastCaller = securityContext.currentParticipant();
        return Future.failedFuture(new IllegalStateException("fetch failed: connect ECONNREFUSED"));
    }

    /**
     * @return the participant the last search was called as
     */
    public Participant lastCaller() {
        return lastCaller;
    }
}
