package org.kinotic.summit42.internal.api.proxies;

import io.vertx.core.Future;
import org.kinotic.core.api.annotations.Proxy;
import org.kinotic.core.api.annotations.Version;
import org.kinotic.core.api.annotations.Zone;
import org.kinotic.domain.api.utils.DomainUtil;

/**
 * Email sign-ups started with a verification template Summit 42 names, from the system server's
 * {@code SystemSignUpService}. The service refuses a call made for a participant, so Summit 42 calls it as itself.
 */
@Proxy(namespace = "org.kinotic.system.api.services", name = "SystemSignUpService")
@Zone(DomainUtil.SYSTEM_API_ZONE)
@Version("1.0.0")
public interface SystemSignUpServiceProxy {

    Future<Void> initiateLocalSignUp(String email, String displayName, String emailTemplateId);
}
