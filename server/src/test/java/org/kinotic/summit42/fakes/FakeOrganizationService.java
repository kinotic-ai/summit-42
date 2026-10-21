package org.kinotic.summit42.fakes;

import lombok.RequiredArgsConstructor;
import org.kinotic.core.api.crud.OffsetPageable;
import org.kinotic.core.api.crud.Page;
import org.kinotic.core.api.crud.Pageable;
import org.kinotic.core.api.security.SecurityContext;
import org.kinotic.domain.api.model.security.participant.OrganizationParticipant;

import java.util.List;

/**
 * A fake of a management service that, like the real one, acts on the organization of the organization participant
 * the call is made as.
 */
@RequiredArgsConstructor
public abstract class FakeOrganizationService {

    private final SecurityContext securityContext;

    /**
     * @return the caller's organization; fails the call when it is not made as an organization participant
     */
    protected String organizationId() {
        return securityContext.requireParticipant(OrganizationParticipant.class).getOrganizationId();
    }

    /**
     * @return the page of the items the offset pageable selects
     */
    protected static <T> Page<T> page(List<T> items, Pageable pageable) {
        int from = Math.min(items.size(), ((OffsetPageable) pageable).getPageNumber() * pageable.getPageSize());
        int to = Math.min(items.size(), from + pageable.getPageSize());
        return new Page<>(List.copyOf(items.subList(from, to)), (long) items.size());
    }
}
