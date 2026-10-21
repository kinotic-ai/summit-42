package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Publish;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.domain.api.model.Application;
import org.kinotic.domain.api.utils.DomainUtil;

/**
 * Publishes {@link FakeApplicationService} under the management server's {@code ApplicationService}, with the functions
 * Summit 42 calls.
 */
@Publish(namespace = "org.kinotic.management.api.services", name = "ApplicationService")
@Zone(DomainUtil.MANAGEMENT_API_ZONE)
@Version("1.0.0")
public interface PublishedApplicationService {

    Future<Application> createSync(Application application);

    Future<Page<Application>> findAll(Pageable pageable);

    Future<Void> deleteById(String id);
}
