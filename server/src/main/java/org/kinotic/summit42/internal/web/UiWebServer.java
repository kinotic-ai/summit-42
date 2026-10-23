package org.kinotic.summit42.internal.web;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.healthchecks.HealthCheckHandler;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.domain.api.services.security.SignUpService;
import org.kinotic.summit42.api.config.SslProperties;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.kinotic.summit42.internal.api.services.Summit42EmailTemplates;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Serves the Summit 42 UI from the jar's {@code webroot}, answering every path that is not a file with
 * {@code index.html} so the UI's own router handles it. {@code POST /api/signup} with {@code {email, displayName}}
 * starts an email sign-up, emailing a link to the UI's {@code /signup/verify} page, and answers 200, or 400 with
 * {@code {error}}; it admits cross-origin calls from {@code summit42.uiBaseUrl}, so a UI served elsewhere can call it.
 * {@code GET /health} answers 200 while every registered health check passes, and 503 otherwise. The port serves TLS
 * when {@code summit42.ssl.enabled} is set.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UiWebServer {

    public static final int PORT = 8080;

    private static final String SIGN_UP_PATH = "/api/signup";
    private static final String WEBROOT = "webroot";
    private static final long SIGN_UP_BODY_LIMIT = 16 * 1024;

    private final Vertx vertx;
    private final HealthChecks healthChecks;
    private final SignUpService signUpService;
    private final Summit42EmailTemplates emailTemplates;
    private final Summit42Properties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Router router = Router.router(vertx);
        router.get("/health").handler(HealthCheckHandler.createWithHealthChecks(healthChecks));
        // on every method, so the browser's OPTIONS preflight is answered before the POST route
        router.route(SIGN_UP_PATH).handler(CorsHandler.create()
                                                      .addOrigin(properties.getUiBaseUrl())
                                                      .allowedMethod(HttpMethod.POST)
                                                      .allowedHeader("Content-Type")
                                                      .allowCredentials(true));
        router.post(SIGN_UP_PATH)
              .handler(BodyHandler.create().setBodyLimit(SIGN_UP_BODY_LIMIT))
              .handler(this::handleSignUp);
        router.route().handler(StaticHandler.create(WEBROOT).setIndexPage("index.html"));
        router.get().handler(ctx -> ctx.response()
                                       .putHeader("Content-Type", "text/html; charset=utf-8")
                                       .sendFile(WEBROOT + "/index.html"));
        HttpServerOptions options = new HttpServerOptions();
        SslProperties ssl = properties.getSsl();
        if (ssl.isEnabled()) {
            options.setSsl(true)
                   .setKeyCertOptions(new PemKeyCertOptions().setCertPath(ssl.getCertPath()).setKeyPath(ssl.getKeyPath()));
        }
        HttpServer server = vertx.createHttpServer(options).requestHandler(router);
        server.listen(PORT)
              .onSuccess(s -> log.info("Serving the Summit 42 UI on port {}", s.actualPort()))
              .onFailure(e -> log.error("The Summit 42 UI server could not listen on port {}", PORT, e));
    }

    private void handleSignUp(RoutingContext ctx) {
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            body = null;
        }
        if (body == null) {
            respond(ctx, 400, new JsonObject().put("error", "Invalid request body"));
            return;
        }
        String email = body.getString("email");
        String displayName = body.getString("displayName");
        // composed on a succeeded future, so a request the sign-up rejects as it is called fails it too
        Future.succeededFuture()
              .compose(v -> signUpService.initiateLocalSignUp(email, displayName, emailTemplates.getVerification()))
              .onSuccess(v -> respond(ctx, 200, new JsonObject().put("message", "Verification email sent. Please check your inbox.")))
              .onFailure(e -> {
                  log.warn("Sign-up failed: {}", e.getMessage());
                  respond(ctx, 400, new JsonObject().put("error", e.getMessage()));
              });
    }

    private static void respond(RoutingContext ctx, int status, JsonObject body) {
        ctx.response()
           .setStatusCode(status)
           .putHeader("Content-Type", "application/json")
           .end(body.encode());
    }
}
