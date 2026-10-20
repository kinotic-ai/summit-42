package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.utils.DomainUtil;
import org.kinotic.management.api.model.ProjectRepoToken;

/**
 * Write tokens for projects' repositories, from the system server's {@code ProjectRepoCredentialService}. The
 * service trusts the organization it is given, so callers pass only the organization of a participant this server
 * authenticated.
 */
@Proxy(namespace = "org.kinotic.system.api.services.deployment", name = "ProjectRepoCredentialService")
@Zone(DomainUtil.SYSTEM_API_ZONE)
@Version("1.0.0")
public interface ProjectRepoCredentialServiceProxy {

    Future<ProjectRepoToken> issueWriteToken(String organizationId, String projectId);
}
