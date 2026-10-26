package org.kinotic.summit42.internal.api.services;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.security.ApplicationOidcConfiguration;
import org.kinotic.domain.api.model.security.OidcConfiguration;
import org.kinotic.domain.api.model.security.OwnedOidcConfiguration;
import org.kinotic.domain.api.model.security.PasswordConfiguration;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;
import org.kinotic.management.api.services.security.OwnedOidcConfigurationService;
import org.kinotic.management.api.services.security.OwnedPasswordConfigurationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Who can sign in to an app of the user's organization, and how: its email and password and the identity providers
 * its login offers, every one of which Summit 42 keeps saying the same about whether anyone may sign up. Each call
 * acts for the participant given, so Kinotic holds it to the participant's organization.
 */
@Component
@RequiredArgsConstructor
public class AppSignInService {

    // an organization holds few providers, so each kind is read in one page
    private static final int PAGE_SIZE = 1000;

    private final Vertx vertx;
    private final SecurityContext securityContext;
    private final OwnedPasswordConfigurationService passwordConfigurationService;
    private final OwnedOidcConfigurationService oidcConfigurationService;

    /**
     * Gives a new app an email and a password, offered at its login from the start, which signs new people up as
     * {@code openSignUp} says.
     */
    public Future<Void> create(OrganizationParticipant participant, String applicationId, boolean openSignUp) {
        PasswordConfiguration password = new PasswordConfiguration().setApplicationId(applicationId)
                                                                    .setEnabled(true)
                                                                    .setCreateUserOnSignIn(openSignUp);
        return callAs(participant, () -> passwordConfigurationService.save(password)).mapEmpty();
    }

    /**
     * @return how people sign in to the app now
     */
    public Future<AppSignIn> find(OrganizationParticipant participant, String applicationId) {
        Future<PasswordConfiguration> password = findPassword(participant, applicationId);
        Future<List<OwnedOidcConfiguration>> providers = findProviders(participant, applicationId);
        return Future.all(password, providers).map(v -> toSignIn(password.result(), providers.result()));
    }

    /**
     * Sets whether anyone may sign up at the app, on its email and password and on each identity provider its login
     * offers, so every one of them says the same. Fails with an {@link IllegalStateException} while the app has
     * neither, since the choice is kept on them.
     *
     * @return how people sign in to the app from now on
     */
    public Future<AppSignIn> setOpenSignUp(OrganizationParticipant participant, String applicationId, boolean openSignUp) {
        Future<PasswordConfiguration> password = findPassword(participant, applicationId);
        Future<List<OwnedOidcConfiguration>> providers = findProviders(participant, applicationId);
        return Future.all(password, providers).compose(v -> {
            Validate.validState(password.result() != null || !providers.result().isEmpty(),
                                "The app offers no way to sign in yet. Add an email and a password, or a sign-in provider,"
                                + " on the app's Settings page in Summit 42 first.");
            // one save at a time, so a refused save leaves the ones before it changed and the ones after it as they were
            Future<PasswordConfiguration> savedPassword;
            if (password.result() != null) {
                PasswordConfiguration changed = password.result().setCreateUserOnSignIn(openSignUp);
                savedPassword = callAs(participant, () -> passwordConfigurationService.save(changed));
            } else {
                savedPassword = Future.succeededFuture();
            }
            Future<List<OwnedOidcConfiguration>> savedProviders = savedPassword.compose(p -> Future.succeededFuture(new ArrayList<>()));
            for (OwnedOidcConfiguration provider : providers.result()) {
                provider.setCreateUserOnSignIn(openSignUp);
                // saved without a secret, so the one held stays as it is
                savedProviders = savedProviders.compose(saved -> callAs(participant, () -> oidcConfigurationService.save(provider, null))
                        .map(result -> {
                            saved.add(result);
                            return saved;
                        }));
            }
            return savedProviders.map(saved -> toSignIn(savedPassword.result(), saved));
        });
    }

    // the app's own login's email and password, or null while it has none
    private Future<PasswordConfiguration> findPassword(OrganizationParticipant participant, String applicationId) {
        return callAs(participant, () -> passwordConfigurationService.findAll(Pageable.create(0, PAGE_SIZE, null)))
                .map(page -> page.getContent().stream()
                                 .filter(password -> applicationId.equals(password.getApplicationId()) && password.getTenantId() == null)
                                 .findFirst()
                                 .orElse(null));
    }

    // the identity providers the app's own login offers, in the order they were added
    private Future<List<OwnedOidcConfiguration>> findProviders(OrganizationParticipant participant, String applicationId) {
        return callAs(participant, () -> oidcConfigurationService.findAll(Pageable.create(0, PAGE_SIZE, null)))
                .map(page -> page.getContent().stream()
                                 .filter(provider -> provider instanceof ApplicationOidcConfiguration ofApplication
                                                     && applicationId.equals(ofApplication.getApplicationId()))
                                 .toList());
    }

    // every provider of the login says the same about signing up, so the email and password answers for all, or the
    // providers when there is none
    private static AppSignIn toSignIn(PasswordConfiguration password, List<OwnedOidcConfiguration> providers) {
        boolean openSignUp = password != null
                ? password.isCreateUserOnSignIn()
                : providers.stream().anyMatch(OwnedOidcConfiguration::isCreateUserOnSignIn);
        return new AppSignIn(password != null && password.isEnabled(),
                             providers.stream().filter(OidcConfiguration::isEnabled).map(OidcConfiguration::getName).toList(),
                             openSignUp);
    }

    // the call is made as the participant, and completes on the caller's Vert.x context
    private <T> Future<T> callAs(OrganizationParticipant participant, Supplier<Future<T>> call) {
        return Future.fromCompletionStage(securityContext.callAs(participant, () -> call.get().toCompletionStage()),
                                          vertx.getOrCreateContext());
    }
}
