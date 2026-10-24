package org.kinotic.summit42.fakes;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.kinotic.core.api.exceptions.AuthorizationException;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.management.api.model.telemetry.LogQuery;
import org.kinotic.management.api.services.telemetry.LogService;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the lines each workload logged in memory and answers a history query the way Loki's {@code query_range} does:
 * the newest lines of the range up to its limit, newest first, under the stream of the workload. Like the real service,
 * it reads only the caller's own organization's logs.
 */
public abstract class FakeLogService extends FakeOrganizationService implements LogService {

    private final Map<String, List<LoggedLine>> lines = new ConcurrentHashMap<>();

    public FakeLogService(SecurityContext securityContext) {
        super(securityContext);
    }

    @Override
    public Future<Buffer> history(LogQuery query) {
        if (!organizationId().equals(query.getOrganizationId())) {
            throw new AuthorizationException("Access denied");
        }
        JsonArray values = new JsonArray();
        lines.getOrDefault(query.getOrganizationId() + "/" + query.getWorkloadId(), List.of()).stream()
             .filter(line -> line.at().toEpochMilli() >= query.getStart() && line.at().toEpochMilli() < query.getEnd())
             .sorted(Comparator.comparing(LoggedLine::at).reversed())
             .limit(query.getLimit())
             .forEach(line -> values.add(new JsonArray().add(String.valueOf(line.at().getEpochSecond() * 1_000_000_000L + line.at().getNano()))
                                                        .add(line.text())));
        JsonArray result = new JsonArray();
        if (!values.isEmpty()) {
            result.add(new JsonObject().put("stream", new JsonObject().put("workload_id", query.getWorkloadId())).put("values", values));
        }
        return Future.succeededFuture(new JsonObject().put("status", "success")
                                                      .put("data", new JsonObject().put("resultType", "streams").put("result", result))
                                                      .toBuffer());
    }

    /**
     * Records a line the workload of the organization logged at the time given.
     */
    public void log(String organizationId, String workloadId, Instant at, String text) {
        lines.computeIfAbsent(organizationId + "/" + workloadId, key -> new CopyOnWriteArrayList<>()).add(new LoggedLine(at, text));
    }
}
