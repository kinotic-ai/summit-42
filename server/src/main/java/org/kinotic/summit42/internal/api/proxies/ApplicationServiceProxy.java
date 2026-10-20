package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.domain.api.model.Application;
import org.kinotic.domain.api.utils.DomainUtil;

/**
 * The management server's {@code ApplicationService}, acting for the organization of the participant the call is
 * made as.
 */
@Proxy(namespace = "org.kinotic.management.api.services", name = "ApplicationService")
@Zone(DomainUtil.MANAGEMENT_API_ZONE)
@Version("1.0.0")
public interface ApplicationServiceProxy {

    /**
     * Creates the application under the id its name slugifies to, and waits for it to be searchable.
     *
     * @param application the application to create, carrying the organization id of the participant
     * @return the created application; fails when the organization already has an application with that name
     */
    Future<Application> createSync(Application application);

    Future<Page<Application>> findAll(Pageable pageable);

    /**
     * Deletes the application; fails when it has projects.
     */
    Future<Void> deleteById(String id);
}
