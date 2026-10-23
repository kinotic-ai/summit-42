package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.management.api.model.deployment.MicroserviceDeployment;
import org.kinotic.management.api.services.deployment.MicroserviceDeploymentService;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds each organization's microservice deployments in memory, answering them ordered by microservice name.
 */
public abstract class FakeMicroserviceDeploymentService extends FakeOrganizationService implements MicroserviceDeploymentService {

    private final Map<String, List<MicroserviceDeployment>> deployments = new ConcurrentHashMap<>();

    public FakeMicroserviceDeploymentService(SecurityContext securityContext) {
        super(securityContext);
    }

    @Override
    public Future<List<MicroserviceDeployment>> findAllForProject(String projectId) {
        return Future.succeededFuture(deployments.getOrDefault(organizationId(), List.of()).stream()
                                                 .filter(deployment -> projectId.equals(deployment.getProjectId()))
                                                 .sorted(Comparator.comparing(MicroserviceDeployment::getName))
                                                 .toList());
    }

    /**
     * Adds the microservice deployment to its organization.
     */
    public void add(MicroserviceDeployment deployment) {
        deployments.computeIfAbsent(deployment.getOrganizationId(), key -> new CopyOnWriteArrayList<>()).add(deployment);
    }
}
