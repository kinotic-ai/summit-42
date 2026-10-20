package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.model.Application;
import org.kinotic.domain.api.model.TenantManagement;
import org.kinotic.domain.api.utils.DomainUtil;

/**
 * The management server's {@code ApplicationService}, acting for the organization of the participant the call is
 * made as.
 */
@Proxy(namespace = "org.kinotic.management.api.services", name = "ApplicationService")
@Zone(DomainUtil.MANAGEMENT_API_ZONE)
@Version("1.0.0")
public interface ApplicationServiceProxy {

    Future<Application> createApplicationIfNotExist(String name, String description, TenantManagement tenantManagement);
}
