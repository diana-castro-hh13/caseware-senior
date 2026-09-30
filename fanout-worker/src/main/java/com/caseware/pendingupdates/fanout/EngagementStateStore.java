package com.caseware.pendingupdates.fanout;

import java.time.Instant;
import java.util.List;

/**
 * Our tracking table: one row per engagement with its template version.
 *
 * <p>This table, not the in-memory queue, is the real record of what still needs checking. If the
 * worker crashes, nothing is lost: unchecked rows are still marked as unchecked here.
 * The SQL behind each method is in src/main/resources/schema.sql.</p>
 */
public interface EngagementStateStore {

    /**
     * Returns the next batch of engagements we still need to check (UNKNOWN or SUSPECT), skipping
     * any that a worker is working on right now. Pass null to start from the beginning.
     */
    List<EngagementRef> findUnverified(String afterEngagementId, int limit);

    /**
     * Tries to reserve an engagement for this worker for a limited time (the "lease").
     * Returns true only if nobody else has reserved it, it is not marked FAILED, and it was not
     * checked at or after {@code notBefore}. This single check is what stops duplicate work.
     */
    boolean tryClaim(String engagementId, String owner, Instant leaseUntil, Instant notBefore);

    /**
     * Saves the answer and releases the reservation. If we already hold a newer answer
     * (for example, the user applied an update while we were checking), the newer answer is kept.
     */
    void recordVerified(String engagementId, TemplateBinding binding, Instant observedAt);

    /** Releases the reservation without saving anything, so the engagement can be tried again. */
    void releaseClaim(String engagementId, String owner);

    /**
     * Marks the engagement FAILED so a person can look at it. We stop retrying it automatically.
     * It goes back to normal when an operator resets it or when the next event for it arrives.
     */
    void recordPermanentFailure(String engagementId, String reason);
}
