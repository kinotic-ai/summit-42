package org.kinotic.summit42.fakes;

import java.time.Instant;

/**
 * A line a workload logged, held by {@link FakeLogService}.
 *
 * @param at   when it was logged
 * @param text the line
 */
public record LoggedLine(Instant at, String text) {}
