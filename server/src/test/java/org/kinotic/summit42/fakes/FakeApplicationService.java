package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.Application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds each organization's applications in memory, ordered by id.
 */
public class FakeApplicationService extends FakeOrganizationService implements PublishedApplicationService {

    // organization id -> application id -> application
    private final Map<String, Map<String, Application>> applications = new ConcurrentHashMap<>();

    public FakeApplicationService(SecurityContext securityContext) {
        super(securityContext);
    }

    @Override
    public Future<Application> createSync(Application application) {
        String id = idOf(application.getName());
        Future<Application> ret;
        if (applicationsOf(organizationId()).putIfAbsent(id, application.setId(id)) != null) {
            ret = Future.failedFuture(new IllegalArgumentException("An application named " + application.getName()
                                                                   + " already exists"));
        } else {
            ret = Future.succeededFuture(application);
        }
        return ret;
    }

    @Override
    public Future<Page<Application>> findAll(Pageable pageable) {
        List<Application> sorted = applicationsOf(organizationId()).values().stream()
                                                                   .sorted(Comparator.comparing(Application::getId))
                                                                   .toList();
        return Future.succeededFuture(page(sorted, pageable));
    }

    @Override
    public Future<Void> deleteById(String id) {
        applicationsOf(organizationId()).remove(id);
        return Future.succeededFuture();
    }

    /**
     * Adds an application to the organization, as one created earlier.
     */
    public Application add(String organizationId, String name, String description) {
        Application ret = new Application(name, description).setOrganizationId(organizationId).setId(idOf(name));
        applicationsOf(organizationId).put(ret.getId(), ret);
        return ret;
    }

    /**
     * @return the organization's applications by id
     */
    public Map<String, Application> applicationsOf(String organizationId) {
        return applications.computeIfAbsent(organizationId, key -> new ConcurrentHashMap<>());
    }

    // the id the management server slugifies an application's name to
    private static String idOf(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}
