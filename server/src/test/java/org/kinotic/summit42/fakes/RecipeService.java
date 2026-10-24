package org.kinotic.summit42.fakes;

import io.vertx.core.Future;

import java.util.List;

/**
 * A service an app's microservice publishes in the app's zone, which a test publishes with {@link FakeRecipeService}.
 */
public interface RecipeService {

    Future<List<String>> searchRecipes(String query, int count);

    Future<List<String>> searchTheWeb(String query);
}
