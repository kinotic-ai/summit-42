package org.kinotic.summit42.internal.web;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Serves the Summit 42 UI from the jar's {@code webroot}, answering every path that is not a file with
 * {@code index.html} so the UI's own router handles it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UiWebServer {

    public static final int PORT = 8080;

    private static final String WEBROOT = "webroot";

    private final Vertx vertx;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Router router = Router.router(vertx);
        router.get("/health").handler(ctx -> ctx.response().end("OK"));
        router.route().handler(StaticHandler.create(WEBROOT).setIndexPage("index.html"));
        router.get().handler(ctx -> ctx.response()
                                       .putHeader("Content-Type", "text/html; charset=utf-8")
                                       .sendFile(WEBROOT + "/index.html"));
        HttpServer server = vertx.createHttpServer().requestHandler(router);
        server.listen(PORT)
              .onSuccess(s -> log.info("Serving the Summit 42 UI on port {}", s.actualPort()))
              .onFailure(e -> log.error("The Summit 42 UI server could not listen on port {}", PORT, e));
    }
}
