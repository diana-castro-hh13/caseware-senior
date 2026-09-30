package com.caseware.pendingupdates.fanout;

import java.time.Instant;

/**
 * One job: "find out which template version this engagement is on".
 *
 * @param requestedAt when we asked for this job. If the engagement gets checked some other way after
 *                    this moment (for example, a user opened it), we skip the job because the answer is
 *                    already fresh.
 * @param attempt     how many times we have already tried and failed.
 */
public record WorkItem(EngagementRef ref, Instant requestedAt, int attempt) {

    public String engagementId() {
        return ref.engagementId();
    }

    public String firmId() {
        return ref.firmId();
    }

    WorkItem nextAttempt() {
        return new WorkItem(ref, requestedAt, attempt + 1);
    }
}
