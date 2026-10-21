package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.management.api.model.deployment.UiDeployment;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Holds each organization's UI deployments in memory, answering them ordered by UI name.
 */
public class FakeUiDeploymentService extends FakeOrganizationService implements PublishedUiDeploymentService {

    private final Map<String, List<UiDeployment>> deployments = new ConcurrentHashMap<>();

    public FakeUiDeploymentService(SecurityContext securityContext) {
        super(securityContext);
    }

    @Override
    public Future<List<UiDeployment>> findAllForProject(String projectId) {
        return Future.succeededFuture(find(ui -> projectId.equals(ui.getProjectId())));
    }

    @Override
    public Future<List<UiDeployment>> findAllForApplication(String applicationId) {
        return Future.succeededFuture(find(ui -> applicationId.equals(ui.getApplicationId())));
    }

    /**
     * Adds the UI deployment to its organization.
     */
    public void add(UiDeployment deployment) {
        deployments.computeIfAbsent(deployment.getOrganizationId(), key -> new CopyOnWriteArrayList<>()).add(deployment);
    }

    private List<UiDeployment> find(Predicate<UiDeployment> filter) {
        return deployments.getOrDefault(organizationId(), List.of()).stream()
                          .filter(filter)
                          .sorted(Comparator.comparing(UiDeployment::getName))
                          .toList();
    }
}
