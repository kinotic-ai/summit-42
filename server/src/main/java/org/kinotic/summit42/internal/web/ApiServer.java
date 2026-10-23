package org.kinotic.summit42.internal.web;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.healthchecks.HealthCheckHandler;
import io.vertx.ext.healthchecks.HealthChecks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kinotic.summit42.api.config.SslProperties;
import org.kinotic.summit42.api.config.Summit42Properties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Summit 42's HTTP port 8080. {@code GET /health} answers 200 while every registered health check passes, and 503
 * otherwise. The port serves TLS when {@code summit42.ssl.enabled} is set.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiServer {

    public static final int PORT = 8080;

    private final Vertx vertx;
    private final HealthChecks healthChecks;
    private final Summit42Properties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Router router = Router.router(vertx);
        router.get("/health").handler(HealthCheckHandler.createWithHealthChecks(healthChecks));
        HttpServerOptions options = new HttpServerOptions();
        SslProperties ssl = properties.getSsl();
        if (ssl.isEnabled()) {
            options.setSsl(true)
                   .setKeyCertOptions(new PemKeyCertOptions().setCertPath(ssl.getCertPath()).setKeyPath(ssl.getKeyPath()));
        }
        HttpServer server = vertx.createHttpServer(options).requestHandler(router);
        server.listen(PORT)
              .onSuccess(s -> log.info("Serving the Summit 42 API on port {}", s.actualPort()))
              .onFailure(e -> log.error("The Summit 42 API could not listen on port {}", PORT, e));
    }
}
