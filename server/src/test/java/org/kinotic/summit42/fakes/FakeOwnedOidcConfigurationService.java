package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.security.ApplicationOidcConfiguration;
import org.kinotic.domain.api.model.security.ClientRegistrationType;
import org.kinotic.domain.api.model.security.OidcProviderType;
import org.kinotic.domain.api.model.security.OwnedOidcConfiguration;
import org.kinotic.management.api.services.security.OwnedOidcConfigurationService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds each organization's own OIDC configurations in memory, and manages a registration at Google and Microsoft
 * as the system does.
 */
public abstract class FakeOwnedOidcConfigurationService extends FakeOrganizationService implements OwnedOidcConfigurationService {

    // organization id -> configuration id -> configuration, in the order added
    private final Map<String, Map<String, OwnedOidcConfiguration>> configurations = new ConcurrentHashMap<>();

    public FakeOwnedOidcConfigurationService(SecurityContext securityContext) {
        super(securityContext);
    }

    @Override
    public Future<Page<OwnedOidcConfiguration>> findAll(Pageable pageable) {
        Map<String, OwnedOidcConfiguration> found = configurationsOf(organizationId());
        List<OwnedOidcConfiguration> all;
        synchronized (found) {
            all = List.copyOf(found.values());
        }
        return Future.succeededFuture(page(all, pageable));
    }

    @Override
    public Future<List<OidcProviderType>> findManagedProviders() {
        return Future.succeededFuture(List.of(OidcProviderType.GOOGLE, OidcProviderType.AZURE_AD));
    }

    @Override
    public Future<OwnedOidcConfiguration> save(OwnedOidcConfiguration configuration, String clientSecret) {
        String organizationId = organizationId();
        if (configuration.getId() == null) {
            configuration.setId(UUID.randomUUID().toString());
        }
        configuration.setOrganizationId(organizationId);
        configurationsOf(organizationId).put(configuration.getId(), configuration);
        return Future.succeededFuture(configuration);
    }

    @Override
    public Future<Void> deleteById(String id) {
        configurationsOf(organizationId()).remove(id);
        return Future.succeededFuture();
    }

    /**
     * Adds an enabled provider of one of the organization's apps' logins, through Kinotic's registration, as one
     * saved earlier.
     */
    public ApplicationOidcConfiguration add(String organizationId, String applicationId, String name, OidcProviderType provider) {
        ApplicationOidcConfiguration ret = new ApplicationOidcConfiguration();
        ret.setApplicationId(applicationId).setClientRegistrationType(ClientRegistrationType.MANAGED);
        ret.setOrganizationId(organizationId);
        ret.setId(UUID.randomUUID().toString()).setName(name).setProvider(provider).setEnabled(true);
        configurationsOf(organizationId).put(ret.getId(), ret);
        return ret;
    }

    /**
     * @return the organization's configurations by id
     */
    public Map<String, OwnedOidcConfiguration> configurationsOf(String organizationId) {
        return configurations.computeIfAbsent(organizationId, key -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }
}
