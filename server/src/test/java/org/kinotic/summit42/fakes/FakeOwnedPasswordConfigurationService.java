package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.security.PasswordConfiguration;
import org.kinotic.management.api.services.security.OwnedPasswordConfigurationService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds each organization's password configurations in memory, by the id Kinotic derives from a configuration's scope.
 */
public abstract class FakeOwnedPasswordConfigurationService extends FakeOrganizationService implements OwnedPasswordConfigurationService {

    // organization id -> configuration id -> configuration, in the order added
    private final Map<String, Map<String, PasswordConfiguration>> configurations = new ConcurrentHashMap<>();
    private final Set<String> failingSaves = ConcurrentHashMap.newKeySet();

    public FakeOwnedPasswordConfigurationService(SecurityContext securityContext) {
        super(securityContext);
    }

    @Override
    public Future<Page<PasswordConfiguration>> findAll(Pageable pageable) {
        Map<String, PasswordConfiguration> found = configurationsOf(organizationId());
        List<PasswordConfiguration> all;
        synchronized (found) {
            all = List.copyOf(found.values());
        }
        return Future.succeededFuture(page(all, pageable));
    }

    @Override
    public Future<PasswordConfiguration> save(PasswordConfiguration configuration) {
        String organizationId = organizationId();
        Future<PasswordConfiguration> ret;
        if (failingSaves.contains(organizationId)) {
            ret = Future.failedFuture(new IllegalStateException("The password configuration could not be saved"));
        } else {
            configuration.setOrganizationId(organizationId)
                         .setId(PasswordConfiguration.idOf(organizationId, configuration.getApplicationId(), configuration.getTenantId()));
            configurationsOf(organizationId).put(configuration.getId(), configuration);
            ret = Future.succeededFuture(configuration);
        }
        return ret;
    }

    @Override
    public Future<Void> deleteById(String id) {
        configurationsOf(organizationId()).remove(id);
        return Future.succeededFuture();
    }

    /**
     * Adds the enabled email and password of one of the organization's apps, as one saved earlier.
     */
    public PasswordConfiguration add(String organizationId, String applicationId, boolean openSignUp) {
        PasswordConfiguration ret = new PasswordConfiguration().setId(PasswordConfiguration.idOf(organizationId, applicationId, null))
                                                               .setOrganizationId(organizationId)
                                                               .setApplicationId(applicationId)
                                                               .setEnabled(true)
                                                               .setCreateUserOnSignIn(openSignUp);
        configurationsOf(organizationId).put(ret.getId(), ret);
        return ret;
    }

    /**
     * Fails every save for the organization, as Kinotic refusing the configuration would.
     */
    public void failSaves(String organizationId) {
        failingSaves.add(organizationId);
    }

    /**
     * @return the organization's configurations by id
     */
    public Map<String, PasswordConfiguration> configurationsOf(String organizationId) {
        return configurations.computeIfAbsent(organizationId, key -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }
}
