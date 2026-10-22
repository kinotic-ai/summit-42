package org.kinotic.summit42.fakes;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Answers Anthropic's Messages API on a local port with the replies a test queues, one per request, streamed as
 * server-sent events, and records each request's body. A request with no reply queued gets an HTTP 400.
 */
public class FakeAnthropic implements AutoCloseable {

    private final Queue<Consumer<HttpServerRequest>> replies = new ConcurrentLinkedQueue<>();
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    public FakeAnthropic(Vertx vertx) {
        server = vertx.createHttpServer()
                      .requestHandler(request -> request.body().onSuccess(body -> {
                          requests.add(body.toJsonObject());
                          Consumer<HttpServerRequest> reply = replies.poll();
                          if (reply == null) {
                              fail(request, 400, "No reply is queued");
                          } else {
                              reply.accept(request);
                          }
                      }))
                      .listen(0)
                      .toCompletionStage()
                      .toCompletableFuture()
                      .join();
    }

    /**
     * @return the base URL an LLM configuration's endpoint is set to
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.actualPort();
    }

    /**
     * Queues a reply of text, streamed in the chunks given.
     */
    public void replyWithText(String... chunks) {
        replies.add(request -> {
            List<JsonObject> events = new ArrayList<>();
            events.add(blockStart(new JsonObject().put("type", "text").put("text", "")));
            for (String chunk : chunks) {
                events.add(delta(new JsonObject().put("type", "text_delta").put("text", chunk)));
            }
            stream(request, events, "end_turn");
        });
    }

    /**
     * Queues a reply that calls the tool with no arguments.
     */
    public void replyWithToolCall(String toolName) {
        replyWithToolCall(toolName, new JsonObject());
    }

    /**
     * Queues a reply that calls the tool with the arguments given.
     */
    public void replyWithToolCall(String toolName, JsonObject input) {
        replies.add(request -> stream(request,
                                      List.of(blockStart(new JsonObject().put("type", "tool_use")
                                                                         .put("id", "toolu_" + requests.size())
                                                                         .put("name", toolName)
                                                                         .put("input", new JsonObject())),
                                              delta(new JsonObject().put("type", "input_json_delta")
                                                                    .put("partial_json", input.encode()))),
                                      "tool_use"));
    }

    /**
     * Queues a reply that refuses the request with the HTTP status.
     */
    public void replyWithError(int status) {
        replies.add(request -> fail(request, status, "Refused by the test"));
    }

    /**
     * @return the body of each request received, in order
     */
    public List<JsonObject> requests() {
        return requests;
    }

    /**
     * Drops the queued replies and the recorded requests.
     */
    public void reset() {
        replies.clear();
        requests.clear();
    }

    @Override
    public void close() {
        server.close();
    }

    // one content block of the events given, framed by the message's start and end
    private static void stream(HttpServerRequest request, List<JsonObject> blockEvents, String stopReason) {
        request.response()
               .setChunked(true)
               .putHeader("Content-Type", "text/event-stream");
        write(request, new JsonObject().put("type", "message_start")
                                       .put("message", new JsonObject().put("id", "msg_test")
                                                                       .put("type", "message")
                                                                       .put("role", "assistant")
                                                                       .put("model", "claude-test")
                                                                       .put("content", new JsonArray())
                                                                       .putNull("stop_reason")
                                                                       .putNull("stop_sequence")
                                                                       .put("usage", usage())));
        blockEvents.forEach(event -> write(request, event));
        write(request, new JsonObject().put("type", "content_block_stop").put("index", 0));
        write(request, new JsonObject().put("type", "message_delta")
                                       .put("delta", new JsonObject().put("stop_reason", stopReason).putNull("stop_sequence"))
                                       .put("usage", usage()));
        write(request, new JsonObject().put("type", "message_stop"));
        request.response().end();
    }

    private static JsonObject blockStart(JsonObject block) {
        return new JsonObject().put("type", "content_block_start").put("index", 0).put("content_block", block);
    }

    private static JsonObject delta(JsonObject delta) {
        return new JsonObject().put("type", "content_block_delta").put("index", 0).put("delta", delta);
    }

    private static JsonObject usage() {
        return new JsonObject().put("input_tokens", 1).put("output_tokens", 1);
    }

    private static void write(HttpServerRequest request, JsonObject event) {
        request.response().write("event: " + event.getString("type") + "\ndata: " + event.encode() + "\n\n");
    }

    private static void fail(HttpServerRequest request, int status, String message) {
        request.response()
               .setStatusCode(status)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("type", "error")
                                    .put("error", new JsonObject().put("type", "invalid_request_error")
                                                                  .put("message", message))
                                    .encode());
    }
}
